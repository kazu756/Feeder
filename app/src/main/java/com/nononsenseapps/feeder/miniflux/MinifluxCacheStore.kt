package com.nononsenseapps.feeder.miniflux

import android.content.Context
import android.util.AtomicFile
import androidx.room.Room
import androidx.room.withTransaction
import com.nononsenseapps.feeder.blob.blobFile
import com.nononsenseapps.feeder.blob.blobInputStream
import com.nononsenseapps.feeder.blob.blobOutputStream
import com.nononsenseapps.feeder.db.room.Feed
import com.nononsenseapps.feeder.db.room.FeedItem
import com.nononsenseapps.feeder.model.ParsedArticle
import com.nononsenseapps.feeder.model.ParsedAuthor
import com.nononsenseapps.feeder.model.ParsedFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.coroutineContext

fun minifluxProfileId(
    baseUrl: String,
    userId: Long,
): String = cacheHash("${normalizedMinifluxUrl(baseUrl)}\n$userId")

internal fun cacheHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

data class MinifluxCacheSummary(
    val profile: MinifluxProfile,
    val feeds: Int,
    val categories: Int,
    val entries: Int,
    val blobs: Int,
    val syncResult: MinifluxCacheSyncResult? = null,
)

data class MinifluxCacheWriteResult(
    val inserted: Boolean,
    val updated: Boolean,
    val pendingProtected: Boolean,
    val blobUpdated: Boolean,
)

data class MinifluxCachedArticle(
    val item: FeedItem,
    val feed: Feed,
    val identity: MinifluxEntryIdentity,
    val pending: MinifluxReadOutbox? = null,
) {
    val effectiveStatus: String get() = pending?.desiredStatus ?: identity.remoteStatus
}

/** No AppDatabase, Repository, notification, or scheduler reference is permitted here. */
class MinifluxCacheStore(
    private val context: Context,
    preferencesName: String = "miniflux_cache_selection",
) {
    private val databases = mutableMapOf<String, MinifluxCacheDatabase>()
    private val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    @Synchronized
    fun database(profileId: String): MinifluxCacheDatabase {
        require(Regex("[a-f0-9]{64}").matches(profileId))
        return databases.getOrPut(profileId) {
            Room
                .databaseBuilder(context, MinifluxCacheDatabase::class.java, "miniflux_cache_$profileId.db")
                .addMigrations(MinifluxCacheDatabase.MIGRATION_1_2, MinifluxCacheDatabase.MIGRATION_2_3)
                .build()
        }
    }

    @Synchronized
    fun close() {
        databases.values.forEach { it.close() }
        databases.clear()
    }

    fun articleDirectory(profileId: String): File {
        require(Regex("[a-f0-9]{64}").matches(profileId))
        return File(context.filesDir, "miniflux_cache/$profileId/articles")
    }

    suspend fun prepare(
        base: String,
        account: MinifluxAccount,
    ): MinifluxProfile =
        withContext(Dispatchers.IO) {
            require(account.id > 0)
            val id = minifluxProfileId(base, account.id)
            val dao = database(id).cacheDao()
            val profile =
                dao.profile()?.copy(username = account.username)
                    ?: MinifluxProfile(id, normalizedMinifluxUrl(base).toString(), account.id, account.username, System.currentTimeMillis())
            dao.saveProfile(profile)
            check(prefs.edit().putString("profile", id).commit())
            profile
        }

    suspend fun saveCategories(
        profile: MinifluxProfile,
        categories: List<MinifluxCategory>,
    ) {
        val db = database(profile.id)
        db.withTransaction { categories.forEach { db.cacheDao().saveCategory(MinifluxCachedCategory(profile.id, it.id, it.title)) } }
    }

    suspend fun saveFeed(
        profile: MinifluxProfile,
        remote: MinifluxFeed,
        category: MinifluxCategory,
    ) {
        val db = database(profile.id)
        db.withTransaction {
            val dao = db.cacheDao()
            val identity = dao.feedIdentity(profile.id, remote.id)
            val feed = identity?.let { dao.feed(it.localFeedId) } ?: Feed()
            feed.url = java.net.URL(remote.feedUrl)
            require(feed.url.protocol in setOf("http", "https"))
            feed.title = remote.title
            feed.tag = category.title
            if (feed.id == 0L) feed.id = dao.insertFeed(feed) else dao.updateFeed(feed)
            dao.saveFeedIdentity(MinifluxFeedIdentity(profile.id, remote.id, feed.id, category.id, remote.siteUrl))
        }
    }

    suspend fun saveEntry(
        profile: MinifluxProfile,
        entry: MinifluxEntry,
    ) = withContext(Dispatchers.IO) {
        val content = entry.content ?: error("Missing content")
        val db = database(profile.id)
        val dao = db.cacheDao()
        val prior = dao.entryIdentity(profile.id, entry.id)
        val priorItem = prior?.let { dao.item(it.localFeedItemId) }
        val hash = cacheHash(content)
        val writeBody =
            if (prior?.contentHash == hash && prior.blobReady) {
                try {
                    readBody(profile.id, prior.localFeedItemId)
                    false
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    true
                }
            } else {
                true
            }
        var pendingProtected = false
        var metadataChanged = false
        val mapped =
            db.withTransaction {
                val feedIdentity = dao.feedIdentity(profile.id, entry.feedId) ?: error("Unknown feed")
                val old = dao.entryIdentity(profile.id, entry.id)
                val item = old?.let { dao.item(it.localFeedItemId) } ?: FeedItem(firstSyncedTime = Instant.now())
                // Reuse Feeder's title/snippet/date/word-count normalization, without RSS fetching.
                item.updateFromParsedEntry(
                    ParsedArticle(
                        id = entry.id.toString(),
                        url = entry.url,
                        title = entry.title,
                        content_html = content,
                        date_published = entry.publishedAt,
                        author = ParsedAuthor(entry.author),
                    ),
                    "miniflux:${profile.id}:${entry.id}",
                    ParsedFeed(title = null, items = null),
                )
                item.feedId = feedIdentity.localFeedId
                metadataChanged = priorItem != null && (priorItem.plainTitle != item.plainTitle || priorItem.link != item.link || priorItem.author != item.author || priorItem.pubDate != item.pubDate)
                val pending = dao.pending(profile.id, entry.id)
                pendingProtected = pending != null
                val effective = pending?.desiredStatus ?: entry.status
                item.readTime = if (effective == "read") item.readTime ?: Instant.EPOCH else null
                item.bookmarked = entry.starred
                item.notified = true
                if (item.id == 0L) item.id = dao.insertItem(item) else dao.updateItem(item)
                MinifluxEntryIdentity(
                    profile.id,
                    entry.id,
                    item.id,
                    entry.feedId,
                    entry.changedAt,
                    entry.status,
                    entry.starred,
                    entry.createdAt,
                    hash,
                    blobReady = !writeBody,
                    readGeneration = old?.readGeneration ?: 0,
                ).also { dao.saveEntryIdentity(it) }
            }
        coroutineContext.ensureActive()
        if (writeBody) writeBlob(profile.id, mapped.localFeedItemId, content)
        coroutineContext.ensureActive()
        dao.markBlobReady(profile.id, entry.id, mapped.contentHash)
        val updated =
            prior != null &&
                (
                    prior.changedAt != entry.changedAt ||
                        prior.contentHash != hash ||
                        prior.remoteStatus != entry.status ||
                        prior.remoteStarred != entry.starred ||
                        prior.remoteFeedId != entry.feedId ||
                        prior.remoteCreatedAt != entry.createdAt ||
                        metadataChanged
                )
        MinifluxCacheWriteResult(prior == null, updated, pendingProtected, writeBody)
    }

    private fun writeBlob(
        profileId: String,
        localId: Long,
        html: String,
    ) {
        val directory = articleDirectory(profileId).apply { mkdirs() }
        val staging = File(directory.parentFile, "staging/${UUID.randomUUID()}").apply { mkdirs() }
        val temp = blobFile(localId, staging)
        try {
            blobOutputStream(localId, staging).bufferedWriter().use { it.write(html) }
            val atomic = AtomicFile(blobFile(localId, directory))
            val output = atomic.startWrite()
            try {
                temp.inputStream().use { it.copyTo(output) }
                atomic.finishWrite(output)
            } catch (e: Exception) {
                atomic.failWrite(output)
                throw e
            }
        } finally {
            temp.delete()
            staging.delete()
        }
    }

    suspend fun complete(
        profile: MinifluxProfile,
        checkpoint: String? = profile.changedCheckpoint,
    ): MinifluxProfile {
        val dao = database(profile.id).cacheDao()
        val current = dao.profile() ?: profile
        val completed = current.copy(lastSuccessfulSync = System.currentTimeMillis(), changedCheckpoint = checkpoint, bootstrapChangedAt = if (checkpoint == null) current.bootstrapChangedAt else null)
        dao.completeSync(profile.id, checkpoint, completed.lastSuccessfulSync!!)
        return completed
    }

    suspend fun selectedProfile(): MinifluxProfile? =
        withContext(Dispatchers.IO) {
            val id = prefs.getString("profile", null) ?: return@withContext null
            database(id).cacheDao().profile()
        }

    suspend fun summary(profile: MinifluxProfile): MinifluxCacheSummary =
        withContext(Dispatchers.IO) {
            val dao = database(profile.id).cacheDao()
            var offset = 0
            var blobs = 0
            while (true) {
                val page = dao.entries(100, offset)
                if (page.isEmpty()) break
                blobs += page.count { it.blobReady && blobFile(it.localFeedItemId, articleDirectory(profile.id)).isFile }
                offset += page.size
            }
            MinifluxCacheSummary(dao.profile() ?: profile, dao.feedCount(), dao.categoryCount(), dao.entryCount(), blobs)
        }

    suspend fun articles(
        profileId: String,
        offset: Int,
    ): List<MinifluxCachedArticle> =
        withContext(Dispatchers.IO) {
            val dao = database(profileId).cacheDao()
            dao.entries(50, offset).map { identity ->
                val item = dao.item(identity.localFeedItemId) ?: error("Missing item")
                MinifluxCachedArticle(item, dao.feed(item.feedId ?: 0) ?: error("Missing feed"), identity, dao.pending(profileId, identity.remoteEntryId))
            }
        }

    fun observeArticles(
        profileId: String,
        limit: Int,
    ): Flow<List<MinifluxCachedArticle>> {
        val db = database(profileId)
        val dao = db.cacheDao()
        return combine(dao.observeEntries(limit), dao.observePending()) { _, _ ->
            db.withTransaction {
                val result = mutableListOf<MinifluxCachedArticle>()
                var offset = 0
                while (offset < limit) {
                    val page = articles(profileId, offset)
                    result.addAll(page.take(limit - offset))
                    if (page.size < 50) break
                    offset += page.size
                }
                result
            }
        }
    }

    suspend fun readBody(
        profileId: String,
        localId: Long,
    ): String =
        withContext(Dispatchers.IO) {
            val identity = database(profileId).cacheDao().identityForLocal(localId) ?: error("Missing identity")
            check(identity.blobReady)
            val html = blobInputStream(localId, articleDirectory(profileId)).bufferedReader().use { it.readText() }
            check(cacheHash(html) == identity.contentHash)
            html
        }
}
