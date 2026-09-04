package com.nononsenseapps.feeder.ui.compose.unreaddigest

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.viewModelScope
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.base.DIAwareViewModel
import com.nononsenseapps.feeder.openai.OpenAIApi
import com.nononsenseapps.feeder.openai.UnreadDigestArticle
import com.nononsenseapps.feeder.openai.UnreadDigestArticleLink
import com.nononsenseapps.feeder.openai.UnreadDigestContent
import com.nononsenseapps.feeder.openai.buildUnreadDigestPrompt
import com.nononsenseapps.feeder.openai.parseUnreadDigestResponse
import com.nononsenseapps.feeder.ui.compose.text.htmlStringToAnnotatedString
import com.nononsenseapps.feeder.ui.text.MarkdownToHtmlConverter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.instance

class UnreadDigestViewModel(
    di: DI,
) : DIAwareViewModel(di) {
    private val repository: Repository by instance()
    private val openAIApi: OpenAIApi by instance()
    private val generator =
        UnreadDigestGenerator(
            scope = viewModelScope,
            loadArticles = {
                withContext(Dispatchers.IO) {
                    repository.getUnreadDigestPreviews().map {
                        UnreadDigestArticle(
                            id = it.id,
                            title = it.plainTitle,
                            snippet = it.plainSnippet,
                            feedTitle = it.feedDisplayTitle,
                            published = it.pubDate,
                            url = it.link,
                        )
                    }
                }
            },
            createDigest = { prompt -> openAIApi.createUnreadDigest(prompt, repository.openAISettings.value) },
            render = { content ->
                val html = withContext(Dispatchers.Default) { MarkdownToHtmlConverter().convertToHtml(content) }
                htmlStringToAnnotatedString(html)
            },
        )
    val state: StateFlow<UnreadDigestState> = generator.state

    init {
        generate()
    }

    fun generate() {
        generator.generate()
    }
}

internal class UnreadDigestGenerator(
    private val scope: CoroutineScope,
    private val loadArticles: suspend () -> List<UnreadDigestArticle>,
    private val createDigest: suspend (com.nononsenseapps.feeder.openai.UnreadDigestPrompt) -> OpenAIApi.UnreadDigestResult,
    private val render: suspend (String) -> List<AnnotatedString>,
) {
    private val mutableState = MutableStateFlow<UnreadDigestState>(UnreadDigestState.Idle)
    val state: StateFlow<UnreadDigestState> = mutableState.asStateFlow()

    fun generate() {
        if (mutableState.value == UnreadDigestState.Loading) return
        mutableState.value = UnreadDigestState.Loading
        scope.launch {
            try {
                val articles = loadArticles()
                if (articles.isEmpty()) {
                    mutableState.value = UnreadDigestState.Empty
                    return@launch
                }
                val prompt = buildUnreadDigestPrompt(articles)
                if (prompt.articleIds.isEmpty()) {
                    mutableState.value = UnreadDigestState.Error("Unread article metadata exceeded the request limit")
                    return@launch
                }
                when (val result = createDigest(prompt)) {
                    is OpenAIApi.UnreadDigestResult.Success -> {
                        val parsed = parseUnreadDigestResponse(result.content, prompt)
                        val content =
                            parsed.content.flatMap { item ->
                                when (item) {
                                    is UnreadDigestContent.Markdown ->
                                        render(item.value).map(UnreadDigestDisplayContent::Paragraph)
                                    is UnreadDigestContent.ArticleLink ->
                                        listOf(UnreadDigestDisplayContent.ArticleLink(item.value))
                                }
                            }
                        mutableState.value =
                            UnreadDigestState.Success(
                                paragraphs = render(parsed.markdown),
                                articleLinks = parsed.articleLinks,
                                content = content,
                            )
                    }
                    is OpenAIApi.UnreadDigestResult.Error -> mutableState.value = UnreadDigestState.Error(result.content)
                }
            } catch (e: Exception) {
                mutableState.value = UnreadDigestState.Error(e.message ?: "Unknown error")
            }
        }
    }
}

sealed interface UnreadDigestState {
    data object Idle : UnreadDigestState

    data object Loading : UnreadDigestState

    data object Empty : UnreadDigestState

    @Immutable
    data class Success(
        val paragraphs: List<AnnotatedString>,
        val articleLinks: List<UnreadDigestArticleLink>,
        val content: List<UnreadDigestDisplayContent>,
    ) : UnreadDigestState

    @Immutable
    data class Error(
        val message: String,
    ) : UnreadDigestState
}

sealed interface UnreadDigestDisplayContent {
    data class Paragraph(
        val value: AnnotatedString,
    ) : UnreadDigestDisplayContent

    data class ArticleLink(
        val value: UnreadDigestArticleLink,
    ) : UnreadDigestDisplayContent
}
