package com.nononsenseapps.feeder.ui.compose.feedarticle

import kotlinx.coroutines.CancellationException
import java.net.URI

internal enum class ReaderContentSource {
    FULL_TEXT,
    RSS,
}

internal sealed interface ReaderContentResult<out T> {
    data class Content<T>(
        val value: T,
        val source: ReaderContentSource,
    ) : ReaderContentResult<T>

    data object Unavailable : ReaderContentResult<Nothing>
}

internal suspend fun <T> resolveReaderContent(
    loadFullText: (suspend () -> T?)?,
    loadRss: suspend () -> T?,
    isUsable: (T) -> Boolean,
): ReaderContentResult<T> {
    if (loadFullText != null) {
        loadContentOrNull(loadFullText)?.takeIf(isUsable)?.let {
            return ReaderContentResult.Content(it, ReaderContentSource.FULL_TEXT)
        }
    }

    loadContentOrNull(loadRss)?.takeIf(isUsable)?.let {
        return ReaderContentResult.Content(it, ReaderContentSource.RSS)
    }

    return ReaderContentResult.Unavailable
}

internal fun readerRssHtml(
    rssArticleBody: String?,
    snippet: String,
    fullTextRequested: Boolean,
): String? = rssArticleBody ?: snippet.takeUnless { fullTextRequested }

private suspend fun <T> loadContentOrNull(loader: suspend () -> T?): T? =
    try {
        loader()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

internal sealed interface ReaderFallbackAction {
    data class OpenCustomTab(
        val url: String,
    ) : ReaderFallbackAction

    data object InvalidUrl : ReaderFallbackAction

    data object AlreadyHandled : ReaderFallbackAction
}

internal class ReaderFallbackEventGate {
    private var handled = false

    @Synchronized
    fun consume(articleUrl: String?): ReaderFallbackAction {
        if (handled) return ReaderFallbackAction.AlreadyHandled
        handled = true

        return if (articleUrl.isValidHttpArticleUrl()) {
            ReaderFallbackAction.OpenCustomTab(articleUrl!!)
        } else {
            ReaderFallbackAction.InvalidUrl
        }
    }
}

internal fun String?.isValidHttpArticleUrl(): Boolean {
    if (this.isNullOrBlank()) return false

    return try {
        val uri = URI(this)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
    } catch (_: Exception) {
        false
    }
}

sealed interface ArticleUiEvent {
    data class ReaderLoadFailed(
        val articleUrl: String,
    ) : ArticleUiEvent
}
