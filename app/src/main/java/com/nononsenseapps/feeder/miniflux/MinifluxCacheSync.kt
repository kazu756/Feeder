package com.nononsenseapps.feeder.miniflux

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.coroutines.coroutineContext

internal const val MINIFLUX_OVERLAP_SECONDS = 2L
internal const val MINIFLUX_CACHE_TIMEOUT_MS = 15L * 60 * 1000

data class MinifluxCacheSyncResult(
    val mode: String,
    val fetched: Int,
    val inserted: Int,
    val updated: Int,
    val unchanged: Int,
    val deduplicated: Int,
    val pendingProtected: Int,
    val blobsUpdated: Int,
    val pages: Int,
)

/** Manual GET only. Checkpoint commits only after all pages and standard blobs succeed. */
class MinifluxCacheSync(
    private val api: MinifluxApiClient,
    private val store: MinifluxCacheStore,
    private val timeoutMillis: Long = MINIFLUX_CACHE_TIMEOUT_MS,
) {
    private val mutex get() = store.operationMutex

    internal suspend fun cleanupEntries(
        plan: MinifluxEntryCleanupPlan,
        token: String,
        progress: (MinifluxCleanupResult) -> Unit,
    ): MinifluxCleanupResult =
        withTimeout(timeoutMillis) {
            mutex.withLock { MinifluxEntryCleanup(api, store).run(plan, token, progress) }
        }

    internal suspend fun cleanup(
        plan: MinifluxCleanupPlan,
        token: String,
        progress: (MinifluxCleanupResult) -> Unit,
    ): MinifluxCleanupResult =
        withTimeout(timeoutMillis) {
            mutex.withLock { MinifluxFeedCleanup(api, store).run(plan, token, progress) }
        }

    suspend fun deletionDryRun(
        profile: MinifluxProfile,
        token: String,
    ): MinifluxDeletionResult =
        withTimeout(timeoutMillis) {
            mutex.withLock { deletionDryRunLocked(profile, token) }
        }

    internal suspend fun deletionDryRunLocked(
        profile: MinifluxProfile,
        token: String,
        progress: (MinifluxReconciliationTrace) -> Unit = {},
    ): MinifluxDeletionResult = MinifluxDeletionDryRun(api, store).run(profile, token, progress)

    suspend fun sync(
        baseUrl: String,
        token: String,
        progress: (String, Int) -> Unit,
    ): MinifluxCacheSummary = withTimeout(timeoutMillis) { mutex.withLock { syncLocked(baseUrl, token, progress) } }

    /** Caller owns operationMutex. All checkpoint and blob commit rules remain in this worker. */
    internal suspend fun syncLocked(
        baseUrl: String,
        token: String,
        progress: (String, Int) -> Unit,
        maximumPages: Int = Int.MAX_VALUE,
        expectedUserId: Long? = null,
    ): MinifluxCacheSummary =
        withTimeout(timeoutMillis) {
            try {
                val base = normalizedMinifluxUrl(baseUrl)
                validateMinifluxToken(token)

                fun safe(value: String): String = value.replace(token, "[redacted]")
                val account = api.me(base, token)
                if (expectedUserId != null && account.id != expectedUserId) throw MinifluxFailure(MinifluxError.FORBIDDEN)
                val profile = store.prepare(base.toString(), account.copy(username = safe(account.username)))
                val dao = store.database(profile.id).cacheDao()
                val checkpoint = profile.changedCheckpoint?.let(Instant::parse)
                val frozenBootstrap = profile.bootstrapChangedAt?.let(Instant::parse)
                val legacyTimes = if (checkpoint == null && frozenBootstrap == null && profile.lastSuccessfulSync != null) dao.cachedChangeTimes() else emptyList()
                // Old caches may include a later partial M2 run. The maximum is not a safe watermark.
                val bootstrap = frozenBootstrap ?: legacyTimes.map { Instant.parse(checkNotNull(it.changedAt)) }.minOrNull()
                val lower = checkpoint ?: bootstrap
                val needsBaseline = checkpoint == null && profile.lastSuccessfulSync == null
                val mode =
                    if (checkpoint != null) {
                        "incremental"
                    } else if (bootstrap != null && !needsBaseline) {
                        "bootstrap"
                    } else {
                        "initial"
                    }
                progress("$mode boundary", 0)
                // Confirmed in the official API validator: changed_at ordering is supported.
                // Freeze this value before paging; never advance to a later page's maximum.
                val probe = api.cacheEntries(base, token, limit = 1, order = "changed_at")
                require(probe.entries.size <= 1 && probe.total >= probe.entries.size)
                val anchor =
                    probe.entries.singleOrNull()?.let {
                        require(it.id > 0)
                        Instant.parse(checkNotNull(it.changedAt))
                    }
                require(anchor == null || checkpoint == null || anchor >= checkpoint)
                if (checkpoint == null) {
                    (bootstrap ?: anchor)?.let { dao.freezeBootstrap(profile.id, it.toString()) }
                }
                progress("$mode categories", 0)
                val categories = api.categories(base, token).map { it.copy(title = safe(it.title)) }
                require(categories.all { it.id > 0 } && categories.map { it.id }.distinct().size == categories.size)
                store.saveCategories(profile, categories)
                val byCategory = categories.associateBy { it.id }
                progress("$mode feeds", 0)
                val feeds = api.feeds(base, token)
                require(feeds.all { it.id > 0 } && feeds.map { it.id }.distinct().size == feeds.size)
                for (feed in feeds) {
                    coroutineContext.ensureActive()
                    val category = byCategory[feed.category?.id] ?: error("Missing category")
                    store.saveFeed(profile, feed.copy(title = safe(feed.title), feedUrl = safe(feed.feedUrl), siteUrl = safe(feed.siteUrl)), category)
                }
                var fetched = 0
                var inserted = 0
                var updated = 0
                var unchanged = 0
                var deduplicated = 0
                var protected = 0
                var blobs = 0
                var pages = 0
                val seen = mutableSetOf<Long>()

                suspend fun consume(entry: MinifluxEntry) {
                    fetched++
                    require(entry.id > 0 && entry.feedId > 0)
                    if (!seen.add(entry.id)) {
                        deduplicated++
                        return
                    }
                    // Removed entries are retained locally. Reconciliation belongs to M4b.
                    if (entry.status == "removed") return
                    require(entry.status in setOf("read", "unread"))
                    if (anchor != null) require(entry.changedAt != null)
                    ZonedDateTime.parse(entry.publishedAt)
                    entry.changedAt?.let { Instant.parse(it) }
                    entry.createdAt?.let { Instant.parse(it) }
                    val write =
                        store.saveEntry(
                            profile,
                            entry.copy(
                                title = safe(entry.title),
                                url = safe(entry.url),
                                author = entry.author?.let(::safe),
                                content = entry.content?.let(::safe),
                            ),
                        )
                    if (write.inserted) {
                        inserted++
                    } else if (write.updated) {
                        updated++
                    } else {
                        unchanged++
                    }
                    if (write.pendingProtected) protected++
                    if (write.blobUpdated) blobs++
                }
                if (lower == null || needsBaseline) {
                    val baselineTime = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                    for (scope in MinifluxCacheScope.entries) {
                        collectCachePages(scope, baselineTime, fetch = { before, limit ->
                            pages++
                            if (pages > maximumPages) throw MinifluxFailure(MinifluxError.RESPONSE_TOO_LARGE)
                            api.cacheEntries(
                                base,
                                token,
                                status = scope.status,
                                starred = scope.starred,
                                publishedAfter = if (scope == MinifluxCacheScope.RECENT_READ) baselineTime.minusSeconds(30L * 86400).epochSecond else null,
                                beforeId = before,
                                limit = limit,
                            )
                        }, consume = ::consume, progress = { progress("initial ${scope.name} page=${pages + 1}", fetched) })
                    }
                }
                if (lower != null && anchor != null) {
                    val after = (lower.epochSecond - MINIFLUX_OVERLAP_SECONDS).coerceAtLeast(1)
                    // Strict <, integer seconds: include the entire anchor second.
                    val before = Math.addExact(anchor.epochSecond, 1)
                    require(before > after)
                    collectChangedPages(after, before, fetch = { cursor ->
                        pages++
                        if (pages > maximumPages) throw MinifluxFailure(MinifluxError.RESPONSE_TOO_LARGE)
                        progress("$mode page=$pages", fetched)
                        api.cacheEntries(base, token, changedAfter = after, changedBefore = before, beforeId = cursor)
                    }, consume = ::consume)
                }
                coroutineContext.ensureActive()
                val summary = store.summary(profile)
                coroutineContext.ensureActive()
                val completed = store.complete(profile, anchor?.toString() ?: profile.changedCheckpoint)
                summary.copy(profile = completed, syncResult = MinifluxCacheSyncResult(mode, fetched, inserted, updated, unchanged, deduplicated, protected, blobs, pages))
            } catch (e: CancellationException) {
                throw e
            } catch (e: MinifluxFailure) {
                throw e
            } catch (_: Exception) {
                throw MinifluxCacheFailure()
            }
        }
}

internal suspend fun collectChangedPages(
    after: Long,
    before: Long,
    fetch: suspend (Long?) -> MinifluxEntries,
    consume: suspend (MinifluxEntry) -> Unit,
) {
    var cursor: Long? = null
    while (true) {
        coroutineContext.ensureActive()
        val page = fetch(cursor)
        require(page.entries.size <= 100 && page.total >= page.entries.size)
        val ids = page.entries.map { it.id }
        require(ids.all { it > 0 && (cursor == null || it < cursor) } && ids.zipWithNext().all { (a, b) -> a > b })
        if (ids.isEmpty()) return
        for (entry in page.entries) {
            val changed = Instant.parse(checkNotNull(entry.changedAt))
            require(changed > Instant.ofEpochSecond(after) && changed < Instant.ofEpochSecond(before))
            coroutineContext.ensureActive()
            consume(entry)
        }
        cursor = ids.last()
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
