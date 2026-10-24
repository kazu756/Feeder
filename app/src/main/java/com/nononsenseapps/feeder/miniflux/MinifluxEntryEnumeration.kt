package com.nononsenseapps.feeder.miniflux

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl

internal const val MINIFLUX_ENTRY_CONFIRM_LIMIT = 50

/** Offset enumeration is accepted only with stable total, strict IDs, no duplicates and a verified end. */
internal suspend fun enumerateMinifluxIds(
    api: MinifluxApiClient,
    base: HttpUrl,
    token: String,
): Set<Long> {
    val result = mutableSetOf<Long>()
    for (status in listOf("read", "unread")) {
        var offset = 0
        var total: Long? = null
        var previous = Long.MAX_VALUE
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = api.entryIds(base, token, status, offset)
            require(page.total in 0..Int.MAX_VALUE.toLong() && (total == null || page.total == total) && page.ids.size <= 10000)
            total = page.total
            require(offset.toLong() + page.ids.size <= page.total)
            if (page.ids.isEmpty()) {
                require(offset.toLong() == page.total)
                break
            }
            for (id in page.ids) {
                require(id > 0 && id < previous && result.add(id))
                previous = id
            }
            offset = Math.addExact(offset, page.ids.size)
        }
    }
    return result
}
