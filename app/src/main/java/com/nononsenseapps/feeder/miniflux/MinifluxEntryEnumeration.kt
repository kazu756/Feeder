package com.nononsenseapps.feeder.miniflux

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl

internal const val MINIFLUX_ENTRY_CONFIRM_LIMIT = 50

/** Stable total, strict IDs, no duplicates, and every ID promised by total. */
internal suspend fun enumerateMinifluxIdSet(
    api: MinifluxApiClient,
    base: HttpUrl,
    token: String,
    status: String?,
    starred: Boolean? = null,
    onRequest: (Int, Int) -> Unit = { _, _ -> },
    onPage: (MinifluxEntryIds) -> Unit = {},
): Set<Long> {
    val result = mutableSetOf<Long>()
    var offset = 0
    var total: Long? = null
    var previous = Long.MAX_VALUE
    var pageNumber = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        onRequest(offset, ++pageNumber)
        val page = api.entryIds(base, token, status, offset, limit = 10000, starred = starred)
        onPage(page)
        require(page.total in 0..Int.MAX_VALUE.toLong() && (total == null || page.total == total) && page.ids.size <= 10000)
        total = page.total
        require(offset.toLong() + page.ids.size <= page.total)
        for (id in page.ids) {
            require(id > 0 && id < previous && result.add(id))
            previous = id
        }
        offset = Math.addExact(offset, page.ids.size)
        if (offset.toLong() == total) return result
        // A short nonempty page is allowed. An empty page before total is incomplete.
        require(page.ids.isNotEmpty())
    }
}

internal suspend fun enumerateMinifluxIds(
    api: MinifluxApiClient,
    base: HttpUrl,
    token: String,
    onRequest: (String, Int, Int) -> Unit = { _, _, _ -> },
    onPage: (MinifluxEntryIds) -> Unit = {},
): Set<Long> {
    val result = mutableSetOf<Long>()
    for (status in listOf("read", "unread")) {
        val ids = enumerateMinifluxIdSet(api, base, token, status, onRequest = { offset, page -> onRequest(status, offset, page) }, onPage = onPage)
        require(ids.none { it in result })
        result.addAll(ids)
    }
    return result
}
