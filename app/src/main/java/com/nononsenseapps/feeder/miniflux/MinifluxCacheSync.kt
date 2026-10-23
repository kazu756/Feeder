package com.nononsenseapps.feeder.miniflux

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.coroutines.coroutineContext

/** Only manually called. Repeated bounded scopes repair missing blobs without URL/GUID matching. */
class MinifluxCacheSync(
    private val api: MinifluxApiClient,
    private val store: MinifluxCacheStore,
) {
    private val mutex = Mutex()

    suspend fun sync(
        baseUrl: String,
        token: String,
        progress: (String, Int) -> Unit,
    ): MinifluxCacheSummary =
        mutex.withLock {
            try {
                val base = normalizedMinifluxUrl(baseUrl)
                validateMinifluxToken(token)

                fun safe(value: String): String = value.replace(token, "[redacted]")
                val account = api.me(base, token)
                val profile = store.prepare(base.toString(), account.copy(username = safe(account.username)))
                progress("categories", 0)
                val categories = api.categories(base, token).map { it.copy(title = safe(it.title)) }
                require(categories.all { it.id > 0 } && categories.map { it.id }.distinct().size == categories.size)
                store.saveCategories(profile, categories)
                val byCategory = categories.associateBy { it.id }
                progress("feeds", 0)
                val feeds = api.feeds(base, token)
                require(feeds.all { it.id > 0 } && feeds.map { it.id }.distinct().size == feeds.size)
                for (feed in feeds) {
                    coroutineContext.ensureActive()
                    val category = byCategory[feed.category?.id] ?: error("Missing category")
                    store.saveFeed(profile, feed.copy(title = safe(feed.title), feedUrl = safe(feed.feedUrl), siteUrl = safe(feed.siteUrl)), category)
                }
                val feedIds = feeds.map { it.id }.toSet()
                val syncTime = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                for (scope in MinifluxCacheScope.entries) {
                    var serverTotal: Long? = null
                    collectCachePages(scope, syncTime, fetch = { before, limit ->
                        api
                            .cacheEntries(
                                base,
                                token,
                                status = scope.status,
                                starred = scope.starred,
                                publishedAfter = if (scope == MinifluxCacheScope.RECENT_READ) syncTime.minusSeconds(30L * 86400).epochSecond else null,
                                beforeId = before,
                                limit = limit,
                            ).also { if (serverTotal == null) serverTotal = it.total }
                    }, consume = { entry ->
                        require(entry.feedId in feedIds && entry.id > 0 && entry.status in setOf("read", "unread"))
                        ZonedDateTime.parse(entry.publishedAt)
                        entry.changedAt?.let { ZonedDateTime.parse(it) }
                        entry.createdAt?.let { ZonedDateTime.parse(it) }
                        store.saveEntry(
                            profile,
                            entry.copy(
                                title = safe(entry.title),
                                url = safe(entry.url),
                                author = entry.author?.let(::safe),
                                content = entry.content?.let(::safe),
                            ),
                        )
                    }, progress = { progress("${scope.name} (total=${serverTotal ?: "?"})", it) })
                }
                coroutineContext.ensureActive()
                store.complete(profile)
                store.summary(profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MinifluxFailure) {
                throw e
            } catch (_: Exception) {
                throw MinifluxCacheFailure()
            }
        }
}

enum class MinifluxCacheScope(
    val status: String?,
    val starred: Boolean?,
    val maximum: Int?,
) {
    UNREAD("unread", null, null),
    STARRED(null, true, null),
    RECENT_READ("read", null, 1000),
}

/** ID-keyset paging avoids offset drift; reject ignored filters/cursors rather than silently truncating. */
internal suspend fun collectCachePages(
    scope: MinifluxCacheScope,
    now: Instant,
    fetch: suspend (Long?, Int) -> MinifluxEntries,
    consume: suspend (MinifluxEntry) -> Unit,
    progress: (Int) -> Unit,
) {
    var before: Long? = null
    var count = 0
    while (scope.maximum == null || count < scope.maximum) {
        coroutineContext.ensureActive()
        val limit = minOf(100, scope.maximum?.minus(count) ?: 100)
        progress(count)
        val page = fetch(before, limit)
        require(page.total >= page.entries.size && page.entries.size <= limit)
        val ids = page.entries.map { it.id }
        require(ids.all { it > 0 && (before == null || it < before!!) } && ids.zipWithNext().all { (a, b) -> a > b })
        if (ids.isEmpty()) break
        for (entry in page.entries) {
            coroutineContext.ensureActive()
            require(scope.status == null || entry.status == scope.status)
            require(scope.starred == null || entry.starred == scope.starred)
            require(scope != MinifluxCacheScope.RECENT_READ || ZonedDateTime.parse(entry.publishedAt).toInstant() > now.minusSeconds(30L * 86400))
            consume(entry)
            count++
        }
        progress(count)
        before = ids.last()
    }
}

class MinifluxCacheFailure : Exception("Miniflux cache could not be completed; retry manual sync")
