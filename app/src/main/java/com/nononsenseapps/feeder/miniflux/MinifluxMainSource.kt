package com.nononsenseapps.feeder.miniflux

import android.app.Application
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.sqlite.db.SimpleSQLiteQuery
import com.nononsenseapps.feeder.archmodel.Article
import com.nononsenseapps.feeder.archmodel.ArticleKey
import com.nononsenseapps.feeder.archmodel.toFeedListItem
import com.nononsenseapps.feeder.db.room.FEED_ITEM_COLUMNS_WITH_FEED
import com.nononsenseapps.feeder.db.room.FeedItemCursor
import com.nononsenseapps.feeder.db.room.ID_ALL_FEEDS
import com.nononsenseapps.feeder.db.room.ID_RECENTLY_VIEWED_ARTICLES
import com.nononsenseapps.feeder.db.room.ID_SAVED_ARTICLES
import com.nononsenseapps.feeder.db.room.ID_UNSET
import com.nononsenseapps.feeder.model.FeedUnreadCount
import com.nononsenseapps.feeder.model.PREVIEW_COLUMNS
import com.nononsenseapps.feeder.ui.compose.feed.FeedListItem
import com.nononsenseapps.feeder.ui.compose.feedarticle.FeedListFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** Main UI queries only: no RSS database, body reads, network, or subscription writes. */
class MinifluxMainSource(
    private val store: MinifluxCacheStore,
    private val application: Application,
) {
    companion object {
        internal const val STATUS = "COALESCE(r.desiredStatus, e.remoteStatus)"
        internal const val STAR = "COALESCE(s.desiredStarred, e.remoteStarred)"
        internal const val FROM = """
            FROM feed_items JOIN feeds ON feeds.id=feed_items.feed_id
            JOIN miniflux_entry_identity e ON e.localFeedItemId=feed_items.id
            JOIN miniflux_feed_identity f ON f.profileId=e.profileId AND f.remoteFeedId=e.remoteFeedId
            LEFT JOIN miniflux_read_outbox r ON r.profileId=e.profileId AND r.remoteEntryId=e.remoteEntryId
            LEFT JOIN miniflux_star_outbox s ON s.profileId=e.profileId AND s.remoteEntryId=e.remoteEntryId
        """

        internal fun effective(columns: String) =
            columns
                .replace("read_time,", "CASE WHEN $STATUS='unread' THEN NULL ELSE COALESCE(read_time, 0) END AS read_time,")
                .replace("bookmarked,", "$STAR AS bookmarked,")
    }

    private fun dao(profile: String) = store.existingDatabase(profile).cacheDao()

    internal fun where(
        profile: String,
        feed: Long,
        category: String,
        filter: FeedListFilter?,
        search: String,
    ): Pair<String, Array<Any>> {
        val args = mutableListOf<Any>(profile)
        val sql = StringBuilder(" WHERE e.profileId=?")
        when {
            feed == ID_RECENTLY_VIEWED_ARTICLES -> sql.append(" AND 0")
            feed == ID_SAVED_ARTICLES -> sql.append(" AND $STAR=1")
            feed > ID_UNSET -> {
                sql.append(" AND feeds.id=?")
                args.add(feed)
            }
            category.isNotEmpty() -> {
                sql.append(" AND CAST(f.remoteCategoryId AS TEXT)=?")
                args.add(category)
            }
        }
        if (feed != ID_SAVED_ARTICLES && filter != null) {
            if (!filter.read && !filter.recentlyRead) {
                sql.append(if (filter.saved) " AND ($STATUS='unread' OR $STAR=1)" else " AND $STATUS='unread'")
            }
        }
        if (search.isNotBlank()) {
            sql.append(" AND (plain_title LIKE ? ESCAPE '\\' OR plain_snippet LIKE ? ESCAPE '\\')")
            val literal = search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            args.add("%$literal%")
            args.add("%$literal%")
        }
        return sql.toString() to args.toTypedArray()
    }

    fun articles(
        profile: String,
        feed: Long,
        category: String,
        filter: FeedListFilter,
        search: String,
        newest: Boolean,
    ): Flow<PagingData<FeedListItem>> {
        val (where, args) = where(profile, feed, category, filter, search)
        val order = if (newest) "DESC" else "ASC"
        return Pager(PagingConfig(pageSize = 30, initialLoadSize = 60, prefetchDistance = 30)) {
            dao(profile).mainPreviews(SimpleSQLiteQuery("SELECT ${effective(PREVIEW_COLUMNS)} $FROM $where ORDER BY primary_sort_time $order, feed_items.id $order", args))
        }.flow.map { page -> page.map { it.toFeedListItem(application).copy(articleKey = ArticleKey(it.id, profile)) } }
    }

    fun count(
        profile: String,
        feed: Long,
        category: String,
        filter: FeedListFilter? = null,
        search: String = "",
    ): Flow<Int> {
        val (where, args) = where(profile, feed, category, filter, search)
        val unread = if (filter == null) " AND $STATUS='unread'" else ""
        return flow { emitAll(dao(profile).mainCount(SimpleSQLiteQuery("SELECT COUNT(*) $FROM $where $unread", args))) }
    }

    fun article(key: ArticleKey): Flow<Article?> =
        flow {
            emitAll(
                dao(checkNotNull(key.profileId))
                    .mainArticle(
                        SimpleSQLiteQuery(
                            "SELECT ${effective(FEED_ITEM_COLUMNS_WITH_FEED)}, e.contentHash || ':' || e.blobReady AS contentRevision $FROM WHERE e.profileId=? AND feed_items.id=?",
                            arrayOf<Any?>(key.profileId, key.localId),
                        ),
                    ).map { row ->
                        row?.item?.fullTextByDefault = false
                        Article(row?.item, row?.contentRevision)
                    },
            )
        }

    fun drawer(profile: String): Flow<List<FeedUnreadCount>> {
        val unread = "SUM(CASE WHEN $STATUS='unread' THEN 1 ELSE 0 END)"
        val entries = """LEFT JOIN miniflux_entry_identity e ON e.profileId=f.profileId AND e.remoteFeedId=f.remoteFeedId
            LEFT JOIN miniflux_read_outbox r ON r.profileId=e.profileId AND r.remoteEntryId=e.remoteEntryId
            LEFT JOIN miniflux_star_outbox s ON s.profileId=e.profileId AND s.remoteEntryId=e.remoteEntryId"""
        val sql = """
            SELECT feeds.id, feeds.title AS display_title, CAST(f.remoteCategoryId AS TEXT) AS tag, feeds.image_url, $unread AS unread_count, 0 AS expanded
            FROM feeds JOIN miniflux_feed_identity f ON f.localFeedId=feeds.id $entries WHERE f.profileId=? GROUP BY feeds.id
            UNION ALL
            SELECT 0 AS id, c.title AS display_title, CAST(c.remoteId AS TEXT) AS tag, NULL AS image_url, $unread AS unread_count, 1 AS expanded
            FROM miniflux_categories c LEFT JOIN miniflux_feed_identity f ON f.profileId=c.profileId AND f.remoteCategoryId=c.remoteId
            $entries WHERE c.profileId=? GROUP BY c.remoteId
            UNION ALL
            SELECT $ID_SAVED_ARTICLES AS id, '' AS display_title, '' AS tag, NULL AS image_url,
                COALESCE(SUM(CASE WHEN $STATUS='unread' AND $STAR=1 THEN 1 ELSE 0 END), 0) AS unread_count, 0 AS expanded
            $FROM WHERE e.profileId=?
            ORDER BY tag, id
        """
        return flow { emitAll(dao(profile).mainDrawer(SimpleSQLiteQuery(sql, arrayOf(profile, profile, profile)))) }.map { rows ->
            val feeds = rows.filter { it.id > ID_UNSET }
            listOf(FeedUnreadCount(id = ID_ALL_FEEDS, unreadCount = feeds.sumOf { it.unreadCount }), rows.first { it.id == ID_SAVED_ARTICLES }) + rows.filter { it.id != ID_SAVED_ARTICLES }
        }
    }

    suspend fun markSelection(
        profile: String,
        feed: Long,
        category: String,
        filter: FeedListFilter,
        search: String,
        cursor: FeedItemCursor? = null,
        before: Boolean = true,
        newest: Boolean = true,
        mutate: suspend (ArticleKey) -> Unit,
    ) {
        val (where, args) = where(profile, feed, category, filter, search)
        // Bounded pages, never materialize all entries or their bodies. id ordering makes mutations safe while paging.
        var after = 0L
        while (true) {
            val boundary =
                if (cursor == null) {
                    ""
                } else {
                    val op = if (before == newest) ">" else "<"
                    " AND (primary_sort_time $op ? OR (primary_sort_time=? AND feed_items.id $op ?))"
                }
            val extra: Array<Any> = if (cursor == null) emptyArray() else arrayOf<Any>(cursor.primarySortTime.toEpochMilli(), cursor.primarySortTime.toEpochMilli(), cursor.id)
            val ids =
                dao(profile).mainIds(
                    SimpleSQLiteQuery(
                        "SELECT feed_items.id $FROM $where AND feed_items.id>? $boundary ORDER BY feed_items.id LIMIT 100",
                        buildList<Any> {
                            addAll(args)
                            add(after)
                            addAll(extra)
                        }.toTypedArray(),
                    ),
                )
            if (ids.isEmpty()) break
            ids.forEach { mutate(ArticleKey(it, profile)) }
            after = ids.last()
        }
    }
}
