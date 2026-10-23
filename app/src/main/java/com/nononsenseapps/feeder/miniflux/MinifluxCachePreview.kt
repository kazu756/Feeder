package com.nononsenseapps.feeder.miniflux

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.Enclosure
import com.nononsenseapps.feeder.model.html.HtmlLinearizer
import com.nononsenseapps.feeder.model.html.LinearArticle
import com.nononsenseapps.feeder.ui.compose.feedarticle.ReaderView
import com.nononsenseapps.feeder.ui.compose.html.ColumnArticleContent
import com.nononsenseapps.feeder.ui.compose.utils.ScreenType
import com.nononsenseapps.feeder.util.articleBaseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** No Repository/ArticleViewModel: opening has no side effects; explicit edits use the outbox. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinifluxCachePreview(
    store: MinifluxCacheStore,
    profile: MinifluxProfile,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    mutations: MinifluxReadMutationStore = MinifluxReadMutationStore(store),
) {
    var entries by remember(profile.id) { mutableStateOf<List<MinifluxCachedArticle>>(emptyList()) }
    var limit by remember(profile.id) { mutableIntStateOf(50) }
    var more by remember(profile.id) { mutableStateOf(true) }
    var failed by remember(profile.id) { mutableStateOf(false) }
    var selected by remember(profile.id) { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    var mutationFailed by remember { mutableStateOf(false) }

    fun change(entry: MinifluxCachedArticle) {
        scope.launch {
            try {
                mutations.toggleStatus(profile.id, entry.identity.remoteEntryId)
                mutationFailed = false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutationFailed = true
            }
        }
    }
    LaunchedEffect(profile.id, limit) {
        try {
            store.observeArticles(profile.id, limit).collect { page ->
                entries = page
                more = page.size == limit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    Dialog(onDismissRequest = { if (selected != null) selected = null else onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(modifier = modifier.fillMaxSize(), topBar = {
            TopAppBar(title = { Text(stringResource(R.string.miniflux_cache_preview)) }, navigationIcon = {
                TextButton(onClick = { if (selected != null) selected = null else onClose() }) { Text(stringResource(R.string.miniflux_cache_back)) }
            })
        }) { padding ->
            Column(Modifier.padding(padding)) {
                Text(stringResource(R.string.miniflux_cache_read_only), modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                if (mutationFailed) Text(stringResource(R.string.miniflux_read_local_failed))
                val article = entries.firstOrNull { it.identity.remoteEntryId == selected }
                if (article != null) {
                    ReadStateControls(article, onChange = { change(article) })
                    MinifluxCachedReader(store, profile.id, article)
                } else {
                    Text("${profile.baseUrl} · ${profile.username}", modifier = Modifier.padding(8.dp))
                    if (failed) Text(stringResource(R.string.miniflux_cache_body_missing))
                    LazyColumn {
                        items(entries, key = { it.identity.remoteEntryId }) { entry ->
                            Column(Modifier.clickable { selected = entry.identity.remoteEntryId }.padding(12.dp)) {
                                Text(entry.item.plainTitle, style = MaterialTheme.typography.titleMedium)
                                Text("${entry.feed.title} · ${entry.feed.tag}")
                                Text("${entry.item.pubDate ?: ""} · ${entry.effectiveStatus} · starred=${entry.identity.remoteStarred}")
                                ReadStateControls(entry, onChange = { change(entry) })
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                        if (more) {
                            item {
                                TextButton(onClick = { limit += 50 }) { Text(stringResource(R.string.miniflux_cache_more)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadStateControls(
    article: MinifluxCachedArticle,
    onChange: () -> Unit,
) {
    Column {
        TextButton(onClick = onChange) {
            Text(stringResource(if (article.effectiveStatus == "read") R.string.miniflux_mark_unread else R.string.miniflux_mark_read))
        }
        article.pending?.let {
            Text(stringResource(R.string.miniflux_read_pending), style = MaterialTheme.typography.bodySmall)
            it.lastErrorType?.let { error -> Text(error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Standard gzip blob -> existing HTML linearizer -> existing ReaderView and body renderer. */
@Composable
fun MinifluxCachedReader(
    store: MinifluxCacheStore,
    profileId: String,
    article: MinifluxCachedArticle,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val large = stringResource(R.string.failed_to_fetch_full_article_too_large)
    val browser = stringResource(R.string.open_in_web_view)
    val content by produceState<MinifluxPreviewBody?>(null, profileId, article.item.id) {
        value =
            try {
                withContext(Dispatchers.IO) {
                    val html = store.readBody(profileId, article.item.id)
                    val base = articleBaseUrl(article.item.link, article.feed.url.toString())
                    MinifluxPreviewBody.Ready(linearizeMinifluxPreview(html, base, article.item.link ?: "", large, browser))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                MinifluxPreviewBody.Missing
            }
    }
    when (val body = content) {
        null -> Text(stringResource(R.string.miniflux_cache_loading), modifier = modifier)
        MinifluxPreviewBody.Missing -> Text(stringResource(R.string.miniflux_cache_body_missing), modifier = modifier)
        is MinifluxPreviewBody.Ready ->
            ReaderView(
                screenType = ScreenType.SINGLE,
                wordCount = article.item.wordCount,
                onEnclosureClick = {},
                onFeedTitleClick = {},
                enclosure = Enclosure(),
                articleTitle = article.item.plainTitle,
                feedTitle = article.feed.title,
                authorDate = listOfNotNull(article.item.author, article.item.pubDate?.toString()).joinToString(" · "),
                image = null,
                showHeaderImage = false,
                contentImageUrls = emptySet(),
                modifier = modifier,
            ) {
                ColumnArticleContent(articleContent = body.article, articleLink = article.item.link, onLinkClick = { link, index ->
                    if (index == null && (link.startsWith("https://") || link.startsWith("http://"))) runCatching { uriHandler.openUri(link) }
                })
            }
    }
}

internal sealed interface MinifluxPreviewBody {
    data class Ready(
        val article: LinearArticle,
    ) : MinifluxPreviewBody

    data object Missing : MinifluxPreviewBody
}

internal fun linearizeMinifluxPreview(
    html: String,
    base: String,
    link: String,
    large: String,
    browser: String,
): LinearArticle {
    // Technical preview does not automatically fetch external media or run scripts.
    // Stored standard blob remains unmodified for future full Reader integration.
    val document = Jsoup.parse(html, base)
    document.select("img,picture,video,audio,iframe,embed,object,source,script,style,link").remove()
    return HtmlLinearizer(large, browser, link).linearize(document.body().html(), base)
}
