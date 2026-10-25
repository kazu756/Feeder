package com.nononsenseapps.feeder.miniflux

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavDeepLinkRequest
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.paging.AsyncPagingDataDiffer
import androidx.paging.PagingSource
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.core.RequestOptions
import com.aallam.openai.api.model.Model
import com.aallam.openai.api.model.ModelId
import com.nononsenseapps.feeder.ApplicationCoroutineScope
import com.nononsenseapps.feeder.FeederApplication
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.ArticleKey
import com.nononsenseapps.feeder.archmodel.BackendMode
import com.nononsenseapps.feeder.archmodel.BackendModeStore
import com.nononsenseapps.feeder.archmodel.BackendSelection
import com.nononsenseapps.feeder.archmodel.FeedItemStore
import com.nononsenseapps.feeder.archmodel.FeedStore
import com.nononsenseapps.feeder.archmodel.OpenAISettings
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.archmodel.SettingsStore
import com.nononsenseapps.feeder.archmodel.TextToDisplay
import com.nononsenseapps.feeder.blob.blobFullOutputStream
import com.nononsenseapps.feeder.blob.blobOutputStream
import com.nononsenseapps.feeder.db.room.AppDatabase
import com.nononsenseapps.feeder.db.room.BlocklistDao
import com.nononsenseapps.feeder.db.room.Feed
import com.nononsenseapps.feeder.db.room.FeedDao
import com.nononsenseapps.feeder.db.room.FeedItem
import com.nononsenseapps.feeder.db.room.FeedItemDao
import com.nononsenseapps.feeder.db.room.ID_ALL_FEEDS
import com.nononsenseapps.feeder.db.room.ID_RECENTLY_VIEWED_ARTICLES
import com.nononsenseapps.feeder.db.room.ID_SAVED_ARTICLES
import com.nononsenseapps.feeder.model.PREVIEW_COLUMNS
import com.nononsenseapps.feeder.model.TranslationManager
import com.nononsenseapps.feeder.openai.AiArticleTranslator
import com.nononsenseapps.feeder.openai.OpenAIApi
import com.nononsenseapps.feeder.openai.OpenAIClient
import com.nononsenseapps.feeder.ui.MainActivityViewModel
import com.nononsenseapps.feeder.ui.compose.feed.FeedListItem
import com.nononsenseapps.feeder.ui.compose.feedarticle.ArticleScreen
import com.nononsenseapps.feeder.ui.compose.feedarticle.ArticleViewModel
import com.nononsenseapps.feeder.ui.compose.feedarticle.FeedListFilter
import com.nononsenseapps.feeder.ui.compose.feedarticle.OpenAISummaryState
import com.nononsenseapps.feeder.ui.compose.feedarticle.emptyFeedListFilter
import com.nononsenseapps.feeder.ui.compose.navigation.ArticleDestination
import com.nononsenseapps.feeder.ui.compose.navigation.FeedDestination
import com.nononsenseapps.feeder.ui.compose.utils.LocalFoldableHinge
import com.nononsenseapps.feeder.ui.compose.utils.LocalWindowSize
import com.nononsenseapps.feeder.ui.compose.utils.WithAllPreviewProviders
import com.nononsenseapps.feeder.util.DEEP_LINK_BASE_URI
import com.nononsenseapps.feeder.util.FilePathProvider
import com.nononsenseapps.feeder.util.filePathProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.compose.withDI
import org.kodein.di.direct
import org.kodein.di.instance
import org.kodein.di.singleton
import java.io.File
import java.net.URL
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class MinifluxMainRoutingTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<FeederApplication>()
    private val suffix = UUID.randomUUID().toString()
    private val root = File(app.cacheDir, "main_$suffix").apply { mkdirs() }
    private val context =
        object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this

            override fun getFilesDir() = root

            override fun getDatabasePath(name: String) = File(root, "db/$name").also { it.parentFile?.mkdirs() }
        }
    private val prefs = context.getSharedPreferences(suffix, 0)
    private val modes = BackendModeStore(prefs)
    private var cache = MinifluxCacheStore(context, suffix)
    private val fixtureScope = ApplicationCoroutineScope()
    private val rss = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private var source = MinifluxMainSource(cache, app)
    private val featureRequests = java.util.concurrent.CopyOnWriteArrayList<ChatCompletionRequest>()
    private val featureConfigs = java.util.concurrent.CopyOnWriteArrayList<OpenAISettings>()
    private var featureFailure = false
    private var suspendFeature = false
    private val featureApi =
        OpenAIApi("ja") { settings ->
            featureConfigs += settings
            object : OpenAIClient {
                override suspend fun models(requestOptions: RequestOptions?): List<Model> = emptyList()

                override suspend fun chatCompletion(
                    request: ChatCompletionRequest,
                    requestOptions: RequestOptions?,
                ): ChatCompletion {
                    featureRequests += request
                    if (suspendFeature) kotlinx.coroutines.awaitCancellation()
                    check(!featureFailure) { "Fixture connection failure" }
                    val input =
                        request.messages
                            .last()
                            .content
                            .orEmpty()
                    val output =
                        if (input.startsWith("Summarize:")) {
                            "Lang: \"ja\"\n日本語要約"
                        } else {
                            val segments =
                                Json
                                    .parseToJsonElement(input)
                                    .jsonObject
                                    .getValue("segments")
                                    .jsonArray
                            buildJsonObject {
                                put(
                                    "translations",
                                    buildJsonArray {
                                        segments.forEach { segment ->
                                            add(
                                                buildJsonObject {
                                                    put(
                                                        "id",
                                                        segment.jsonObject
                                                            .getValue("id")
                                                            .jsonPrimitive.content,
                                                    )
                                                    put("text", "AI翻訳")
                                                },
                                            )
                                        }
                                    },
                                )
                            }.toString()
                        }
                    return ChatCompletion(
                        id = "fixture",
                        created = 0,
                        model = ModelId("fixture-model"),
                        choices = listOf(ChatChoice(index = 0, message = ChatMessage(role = ChatRole.Assistant, content = output), finishReason = null, logprobs = null)),
                        usage = null,
                        systemFingerprint = null,
                    )
                }
            }
        }
    private val di =
        DI {
            extend(app.di)
            bind<FilePathProvider>(overrides = true) with singleton { filePathProvider(root, root) }
            bind<SharedPreferences>(overrides = true) with instance(prefs)
            bind<BackendModeStore>(overrides = true) with instance(modes)
            bind<ApplicationCoroutineScope>(overrides = true) with instance(fixtureScope)
            bind<MinifluxCacheStore>(overrides = true) with instance(cache)
            bind<MinifluxMainSource>(overrides = true) with instance(source)
            bind<MinifluxReadMutationStore>(overrides = true) with singleton { MinifluxReadMutationStore(cache) }
            bind<MinifluxStarMutationStore>(overrides = true) with singleton { MinifluxStarMutationStore(cache) }
            bind<AppDatabase>(overrides = true) with instance(rss)
            bind<FeedDao>(overrides = true) with instance(rss.feedDao())
            bind<FeedItemDao>(overrides = true) with instance(rss.feedItemDao())
            bind<BlocklistDao>(overrides = true) with instance(rss.blocklistDao())
            bind<SettingsStore>(overrides = true) with singleton { SettingsStore(di).also { it.setAddedFeederNews(true) } }
            bind<FeedStore>(overrides = true) with singleton { FeedStore(di) }
            bind<FeedItemStore>(overrides = true) with singleton { FeedItemStore(di) }
            bind<Repository>(overrides = true) with singleton { Repository(di) }
            bind<OpenAIApi>(overrides = true) with instance(featureApi)
            bind<AiArticleTranslator>(overrides = true) with singleton { AiArticleTranslator(featureApi) }
            bind<TranslationManager>(overrides = true) with singleton { TranslationManager(di) }
        }
    private val repo by lazy { di.direct.instance<Repository>() }
    private val all =
        object : FeedListFilter {
            override val unread = true
            override val saved = false
            override val recentlyRead = false
            override val read = true
        }
    private val time = "2026-10-25T00:00:00Z"

    private suspend fun prepare(): MinifluxProfile {
        val profile = cache.prepare("https://fixture.invalid/", MinifluxAccount(42, "fixture"))
        val category = MinifluxCategory(99, "News")
        cache.saveCategories(profile, listOf(category, MinifluxCategory(100, "Other")))
        cache.saveFeed(profile, MinifluxFeed(5000, "Miniflux Feed", "https://fixture.invalid/rss", category), category)
        cache.saveEntry(profile, MinifluxEntry(9000, 5000, "Miniflux title", "unread", false, time, time, "https://fixture.invalid/a", content = "<p>Miniflux body</p>"))
        return cache.complete(profile, time)
    }

    private fun use(profile: MinifluxProfile) = modes.commit(BackendSelection(BackendMode.MINIFLUX, profile.id))

    private suspend fun rows(
        profile: MinifluxProfile,
        feed: Long = ID_ALL_FEEDS,
        category: String = "",
        filter: FeedListFilter = all,
        search: String = "",
        size: Int = 60,
    ): PagingSource.LoadResult.Page<Int, com.nononsenseapps.feeder.model.PreviewItem> {
        val (where, args) = source.where(profile.id, feed, category, filter, search)
        val query = SimpleSQLiteQuery("SELECT ${MinifluxMainSource.effective(PREVIEW_COLUMNS)} ${MinifluxMainSource.FROM} $where ORDER BY primary_sort_time DESC, feed_items.id DESC", args)
        val result =
            cache
                .database(profile.id)
                .cacheDao()
                .mainPreviews(query)
                .load(PagingSource.LoadParams.Refresh(null, size, false))
        return result as PagingSource.LoadResult.Page
    }

    private suspend fun featureArticle(): Pair<MinifluxProfile, Long> {
        val profile = prepare()
        val id =
            cache
                .database(profile.id)
                .cacheDao()
                .entryIdentity(profile.id, 9000)!!
                .localFeedItemId
        val feed = rss.feedDao().insertFeed(Feed(title = "RSS Feed", url = URL("https://rss.invalid/")))
        rss.feedItemDao().insertFeedItem(FeedItem(id = id, guid = "rss", feedId = feed, plainTitle = "RSS title", link = "https://rss.invalid/a"))
        val files = di.direct.instance<FilePathProvider>()
        files.articleDir.mkdirs()
        files.fullArticleDir.mkdirs()
        blobOutputStream(id, files.articleDir).bufferedWriter().use { it.write("<p>RSS body</p>") }
        blobFullOutputStream(id, files.fullArticleDir).bufferedWriter().use { it.write("<p>RSS original full body</p>") }
        return profile to id
    }

    private fun configureFeatures(server: MockWebServer) {
        val settings = di.direct.instance<SettingsStore>()
        settings.setTranslationApiSettings(OpenAISettings(key = "fixture", baseUrl = server.url("/deepl.com/").toString()))
        settings.setPreferredTranslationLanguage("ja")
        settings.setOpenAiSettings(OpenAISettings(key = "fixture", modelId = "fixture-model"))
    }

    private suspend fun reader(
        key: ArticleKey,
        owners: ViewModelStore,
    ): ArticleViewModel =
        withContext(Dispatchers.Main) {
            ArticleViewModel(di, SavedStateHandle(mapOf("itemId" to key.localId, "profile" to key.profileId))).also { owners.put("reader", it) }
        }

    private fun enqueueTranslation(server: MockWebServer) {
        server.enqueue(MockResponse().setBody("""{"translations":[{"text":"<p>通常翻訳本文</p>","detected_source_language":"EN"}]}"""))
        server.enqueue(MockResponse().setBody("""{"translations":[{"text":"通常翻訳タイトル","detected_source_language":"EN"}]}"""))
    }

    @Test fun readerTranslationAndAiUseSharedServicesWithIsolatedBodiesAndCaches() =
        runBlocking {
            val (profile, id) = featureArticle()
            val server = MockWebServer().apply { start() }
            val owners = ViewModelStore()
            try {
                configureFeatures(server)
                use(profile)
                for (key in listOf(ArticleKey(id), ArticleKey(id, profile.id))) {
                    val model = reader(key, owners)
                    withTimeout(10000) { model.viewState.first { it.showTranslate && it.showTranslateWithAi && it.showSummarize } }
                    enqueueTranslation(server)
                    model.translate()
                    val translated = withTimeout(10000) { model.viewState.first { it.isShowingTranslated && !it.isTranslationLoading } }
                    assertEquals("通常翻訳タイトル", translated.articleTitle)
                    model.translate()
                    withTimeout(5000) { model.viewState.first { !it.isShowingTranslated } }
                    model.translateWithAi()
                    assertEquals("AI翻訳", withTimeout(10000) { model.viewState.first { it.isShowingTranslated && !it.isTranslationLoading } }.articleTitle)
                    // Summary always reads original content, even while AI translation is displayed.
                    model.summarize()
                    val result = withTimeout(10000) { model.viewState.first { it.openAiSummary is OpenAISummaryState.Result } }.openAiSummary as OpenAISummaryState.Result
                    assertTrue(result.value is OpenAIApi.SummaryResult.Success)
                    val input =
                        featureRequests
                            .last()
                            .messages
                            .last()
                            .content
                            .orEmpty()
                    assertTrue(input.contains(if (key.isMiniflux) "Miniflux body" else "RSS original full body"))
                    assertFalse(input.contains("AI翻訳"))
                    val aiInput =
                        featureRequests[featureRequests.size - 2]
                            .messages
                            .last()
                            .content
                            .orEmpty()
                    assertTrue(aiInput.contains(if (key.isMiniflux) "Miniflux body" else "RSS body"))
                    assertEquals(key, model.articleKey)
                }
                assertEquals(featureRequests[1].messages.first(), featureRequests[3].messages.first())
                assertTrue(featureConfigs.all { it == repo.openAISettings.value })
                assertEquals(4, server.requestCount)
                val requestBodies = (1..4).map { server.takeRequest().body.readUtf8() }
                assertTrue(requestBodies[0].contains("RSS body"))
                assertTrue(requestBodies[2].contains("Miniflux body"))
                assertFalse(requestBodies[2].contains("RSS body"))
                assertEquals("RSS title", rss.feedItemDao().loadFeedItem(id)!!.plainTitle)
                assertEquals("<p>Miniflux body</p>", cache.readBody(profile.id, id))
                assertEquals(
                    0,
                    cache
                        .database(profile.id)
                        .cacheDao()
                        .pending(profile.id)
                        .size,
                )
                assertEquals(
                    0,
                    cache
                        .database(profile.id)
                        .cacheDao()
                        .starPending(profile.id)
                        .size,
                )
                val cacheFiles =
                    di.direct
                        .instance<FilePathProvider>()
                        .cacheDir
                        .resolve("translations")
                        .listFiles()!!
                        .map { it.name }
                assertTrue(cacheFiles.any { it.startsWith("$id.") })
                assertTrue(cacheFiles.any { it.startsWith("miniflux-${profile.id}-$id.") })
                // A recreated Reader retains ArticleKey and reuses cached translation without network.
                withContext(Dispatchers.Main) { owners.clear() }
                modes.commit(BackendSelection())
                val reopened = reader(ArticleKey(id, profile.id), owners)
                withTimeout(10000) { reopened.viewState.first { it.showTranslate } }
                reopened.translate()
                withTimeout(10000) { reopened.viewState.first { it.isShowingTranslated && !it.isTranslationLoading } }
                assertEquals(4, server.requestCount)
                assertEquals(profile.id, reopened.articleKey.profileId)
            } finally {
                withContext(Dispatchers.Main) { owners.clear() }
                server.shutdown()
            }
        }

    @Test fun missingMinifluxBodyDisablesAllFeaturesWithoutLocalFallbackOrApiRequests() =
        runBlocking {
            val (profile, id) = featureArticle()
            val server = MockWebServer().apply { start() }
            val owners = ViewModelStore()
            try {
                configureFeatures(server)
                com.nononsenseapps.feeder.blob
                    .blobFile(id, cache.articleDirectory(profile.id))
                    .delete()
                val model = reader(ArticleKey(id, profile.id), owners)
                val missing = withTimeout(10000) { model.viewState.first { it.textToDisplay == TextToDisplay.FAILED_MISSING_BODY } }
                assertFalse(missing.showTranslate)
                assertFalse(missing.showTranslateWithAi)
                assertFalse(missing.showSummarize)
                model.translate()
                model.translateWithAi()
                model.summarize()
                withTimeout(10000) { model.viewState.first { it.openAiSummary is OpenAISummaryState.Result && !it.isTranslationLoading } }
                assertEquals(0, server.requestCount)
                assertTrue(featureRequests.isEmpty())
                assertEquals("RSS title", rss.feedItemDao().loadFeedItem(id)!!.plainTitle)
            } finally {
                withContext(Dispatchers.Main) { owners.clear() }
                server.shutdown()
            }
        }

    @Test fun readerFeatureErrorsRetryAndCancellationPreserveOriginalAndPending() =
        runBlocking {
            val (profile, id) = featureArticle()
            val server = MockWebServer().apply { start() }
            val owners = ViewModelStore()
            try {
                configureFeatures(server)
                val key = ArticleKey(id, profile.id)
                repo.markArticle(key, true)
                repo.bookmarkArticle(key, true)
                val model = reader(key, owners)
                withTimeout(10000) { model.viewState.first { it.showTranslate } }
                featureFailure = true
                model.summarize()
                val failed = withTimeout(10000) { model.viewState.first { it.openAiSummary is OpenAISummaryState.Result } }
                assertTrue((failed.openAiSummary as OpenAISummaryState.Result).value is OpenAIApi.SummaryResult.Error)
                featureFailure = false
                model.summarize()
                withTimeout(10000) { model.viewState.first { (it.openAiSummary as? OpenAISummaryState.Result)?.value is OpenAIApi.SummaryResult.Success } }
                server.enqueue(MockResponse().setResponseCode(500))
                model.translate()
                withTimeout(10000) { while (server.requestCount < 1) delay(10) }
                withTimeout(10000) { model.viewState.first { !it.isTranslationLoading && !it.isShowingTranslated } }
                enqueueTranslation(server)
                model.translate()
                withTimeout(10000) { model.viewState.first { it.isShowingTranslated && !it.isTranslationLoading } }
                suspendFeature = true
                model.translateWithAi()
                withTimeout(10000) { while (featureRequests.size < 3) delay(10) }
                withContext(Dispatchers.Main) { owners.clear() }
                assertEquals(
                    1,
                    cache
                        .database(profile.id)
                        .cacheDao()
                        .pending(profile.id)
                        .size,
                )
                assertEquals(
                    1,
                    cache
                        .database(profile.id)
                        .cacheDao()
                        .starPending(profile.id)
                        .size,
                )
                assertEquals("<p>Miniflux body</p>", cache.readBody(profile.id, id))
            } finally {
                withContext(Dispatchers.Main) { owners.clear() }
                server.shutdown()
            }
        }

    @Test fun changedMinifluxBodyCancelsInFlightFeatureAndAllowsRetry() =
        runBlocking {
            val (profile, id) = featureArticle()
            val server = MockWebServer().apply { start() }
            val owners = ViewModelStore()
            try {
                configureFeatures(server)
                val model = reader(ArticleKey(id, profile.id), owners)
                withTimeout(10000) { model.viewState.first { it.showTranslateWithAi } }
                suspendFeature = true
                model.translateWithAi()
                withTimeout(5000) { while (featureRequests.isEmpty()) delay(10) }
                cache.saveEntry(profile, MinifluxEntry(9000, 5000, "Changed title", "unread", false, time, time, "https://fixture.invalid/a", content = "<p>Changed body</p>"))
                withTimeout(10000) { model.viewState.first { it.articleTitle == "Changed title" && it.showTranslateWithAi } }
                suspendFeature = false
                model.translateWithAi()
                withTimeout(10000) { model.viewState.first { it.isShowingTranslated && !it.isTranslationLoading } }
                assertTrue(
                    featureRequests
                        .last()
                        .messages
                        .last()
                        .content
                        .orEmpty()
                        .contains("Changed body"),
                )
                assertEquals(0, server.requestCount)
            } finally {
                withContext(Dispatchers.Main) { owners.clear() }
                server.shutdown()
            }
        }

    @Test fun minifluxReaderMenusRunTranslationAndJapaneseSummaryInSharedUi() {
        val (profile, id) = runBlocking { featureArticle() }
        val server = MockWebServer().apply { start() }
        val owners = ViewModelStore()
        configureFeatures(server)
        use(profile)
        val model = runBlocking { reader(ArticleKey(id, profile.id), owners) }
        enqueueTranslation(server)
        compose.setContent {
            withDI(di) {
                CompositionLocalProvider(LocalFoldableHinge provides null, LocalWindowSize provides DpSize(400.dp, 800.dp)) {
                    WithAllPreviewProviders {
                        ArticleScreen({}, {}, model, remember { MainActivityViewModel(di) })
                    }
                }
            }
        }
        try {
            compose.waitUntil(10000) { compose.onAllNodesWithText("Miniflux body", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(app.getString(R.string.open_menu)).performClick()
            compose.onNodeWithText(app.getString(R.string.translate_article)).performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("通常翻訳タイトル", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(app.getString(R.string.open_menu)).performClick()
            compose.onNodeWithText(app.getString(R.string.translate_article_with_ai)).assertExists()
            compose.onNodeWithText(app.getString(R.string.summarize)).performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("日本語要約", substring = true).fetchSemanticsNodes().isNotEmpty() }
        } finally {
            runBlocking { withContext(Dispatchers.Main) { owners.clear() } }
            server.shutdown()
        }
    }

    @After fun cleanup() {
        fixtureScope.cancel()
        cache.close()
        rss.close()
        root.deleteRecursively()
        prefs.edit().clear().commit()
    }

    @Test fun normalMainScreenOpensMinifluxReaderAndWritesReadAndStarOutboxes() {
        val profile = runBlocking { prepare() }
        val local =
            runBlocking {
                val id =
                    cache
                        .database(profile.id)
                        .cacheDao()
                        .entryIdentity(profile.id, 9000)!!
                        .localFeedItemId
                val feed = rss.feedDao().insertFeed(Feed(title = "RSS Feed", url = URL("https://rss.invalid/")))
                rss.feedItemDao().insertFeedItem(FeedItem(id = id, guid = "rss", feedId = feed, plainTitle = "RSS notification title"))
                val dir =
                    di.direct
                        .instance<FilePathProvider>()
                        .articleDir
                        .apply { mkdirs() }
                blobOutputStream(id, dir).bufferedWriter().use { it.write("<p>RSS notification body</p>") }
                id
            }
        lateinit var controller: NavHostController
        use(profile)
        compose.setContent {
            withDI(di) {
                CompositionLocalProvider(LocalFoldableHinge provides null, LocalWindowSize provides DpSize(400.dp, 800.dp)) {
                    WithAllPreviewProviders {
                        val nav = rememberNavController()
                        controller = nav
                        val drawer = rememberLazyListState()
                        val main = remember { MainActivityViewModel(di) }
                        NavHost(navController = nav, startDestination = FeedDestination.route) {
                            FeedDestination.register(this, nav, drawer, main)
                            ArticleDestination.register(this, nav, drawer, main)
                        }
                    }
                }
            }
        }
        try {
            compose.waitUntil(15000) { compose.onAllNodesWithText("Miniflux title", substring = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            throw AssertionError(
                "count=${runBlocking { repo.getCurrentFeedListVisibleItemCount().first() }} error=${repo.mainBackendError.value}\n${compose.onRoot(useUnmergedTree = true).printToString()}",
                e,
            )
        }
        compose.onNodeWithText("Miniflux title", substring = true).performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("readerColumn").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15000) { compose.onAllNodesWithText("Miniflux body", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(app.getString(R.string.open_menu)).performClick()
        compose.onNodeWithText(app.getString(R.string.save_article)).performClick()
        compose.waitUntil(5000) {
            runBlocking {
                cache
                    .database(profile.id)
                    .cacheDao()
                    .starPending(profile.id)
                    .singleOrNull()
                    ?.desiredStarred == true
            }
        }
        compose.onNodeWithContentDescription(app.getString(R.string.open_menu)).performClick()
        compose.onNodeWithText(app.getString(R.string.mark_as_unread)).performClick()
        compose.waitUntil(5000) {
            runBlocking {
                cache
                    .database(profile.id)
                    .cacheDao()
                    .pending(profile.id)
                    .singleOrNull()
                    ?.desiredStatus == "unread"
            }
        }
        // Notifications and the retained RSS widget both use this legacy URI.
        compose.runOnIdle {
            controller.navigate(NavDeepLinkRequest.Builder.fromUri(Uri.parse("$DEEP_LINK_BASE_URI/article/$local")).build())
        }
        compose.waitUntil(15000) { compose.onAllNodesWithText("RSS notification title").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15000) { compose.onAllNodesWithText("RSS notification body", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(BackendMode.MINIFLUX, modes.selection.value.mode)
        runBlocking {
            assertNotNull(
                repo
                    .getArticleFlow(ArticleKey(local))
                    .first()!!
                    .item!!
                    .readTime,
            )
            assertEquals(
                "unread",
                cache
                    .database(profile.id)
                    .cacheDao()
                    .pending(profile.id)
                    .single()
                    .desiredStatus,
            )
        }
    }

    @Test fun projectionUsesJoinedMetadataAndEffectivePendingForFiltersAndCounts() =
        runBlocking {
            val profile = prepare()
            val dao = cache.database(profile.id).cacheDao()
            val initial = rows(profile).data.single()
            assertEquals("Miniflux title", initial.plainTitle)
            assertEquals("Miniflux Feed", initial.feedTitle)
            assertEquals(Instant.parse(time), initial.pubDate!!.toInstant())
            assertNull(initial.readTime)
            assertFalse(initial.bookmarked)
            MinifluxReadMutationStore(cache).setStatus(profile.id, 9000, "read")
            MinifluxStarMutationStore(cache).setStarred(profile.id, 9000, true)
            assertNotNull(rows(profile).data.single().readTime)
            assertTrue(rows(profile).data.single().bookmarked)
            assertEquals(0, source.count(profile.id, ID_ALL_FEEDS, "").first())
            assertEquals(0, rows(profile, filter = emptyFeedListFilter).data.size)
            assertEquals(1, rows(profile, feed = ID_SAVED_ARTICLES).data.size)
            assertEquals(1, dao.pending(profile.id).size)
            assertEquals(1, dao.starPending(profile.id).size)
            MinifluxReadMutationStore(cache).setStatus(profile.id, 9000, "unread")
            MinifluxStarMutationStore(cache).setStarred(profile.id, 9000, false)
            assertEquals(1, rows(profile, filter = emptyFeedListFilter).data.size)
            assertEquals(0, rows(profile, feed = ID_SAVED_ARTICLES).data.size)
            assertEquals(
                1,
                source
                    .drawer(profile.id)
                    .first()
                    .first { it.id > 0 }
                    .unreadCount,
            )
        }

    @Test fun feedCategorySearchAndRecentlyViewedRemainInProfile() =
        runBlocking {
            val profile = prepare()
            val feedId =
                cache
                    .database(profile.id)
                    .cacheDao()
                    .feedIdentity(profile.id, 5000)!!
                    .localFeedId
            assertEquals(1, rows(profile, feed = feedId).data.size)
            assertEquals(0, rows(profile, feed = feedId + 1).data.size)
            assertEquals(1, rows(profile, category = "99").data.size)
            assertEquals(0, rows(profile, category = "100").data.size)
            assertEquals(1, rows(profile, search = "title").data.size)
            assertEquals(0, rows(profile, search = "%").data.size)
            assertEquals(0, rows(profile, feed = ID_RECENTLY_VIEWED_ARTICLES).data.size)
            val drawer = source.drawer(profile.id).first()
            assertEquals("News", drawer.first { it.id == 0L && it.tag == "99" }.displayTitle)
            assertEquals(1, drawer.first { it.id == 0L && it.tag == "99" }.unreadCount)
            assertEquals(0, drawer.first { it.id == 0L && it.tag == "100" }.unreadCount)
        }

    @Test fun threeThousandRowsPageWithoutOpeningAnyBodyAndUseStableOrdering() =
        runBlocking {
            val profile = prepare()
            val db = cache.database(profile.id)
            val dao = db.cacheDao()
            val seed = dao.item(dao.entryIdentity(profile.id, 9000)!!.localFeedItemId)!!
            db.withTransaction {
                repeat(3000) { index ->
                    val id = dao.insertItem(seed.copy(id = 0, guid = "bulk$index", plainTitle = "bulk$index", primarySortTime = Instant.ofEpochMilli(index.toLong())))
                    dao.insertEntryIdentity(MinifluxEntryIdentity(profile.id, 10000L + index, id, 5000, time, "unread", false, time, "missing", false))
                }
            }
            cache.articleDirectory(profile.id).deleteRecursively()
            val first = rows(profile)
            assertEquals(60, first.data.size)
            assertNotNull(first.nextKey)
            assertEquals(3001, source.count(profile.id, ID_ALL_FEEDS, "", all).first())
            assertEquals("Miniflux title", first.data.first().plainTitle)
            assertEquals(
                3001,
                source
                    .drawer(profile.id)
                    .first()
                    .first { it.id == ID_ALL_FEEDS }
                    .unreadCount,
            )
            assertEquals(4, db.openHelper.readableDatabase.version)
            assertEquals(40, rss.openHelper.readableDatabase.version)
        }

    @Test fun mainRepositoryHotSwitchesAndRestoresWithoutChangingEitherSelection() =
        runBlocking {
            val profile = prepare()
            val localFeed = rss.feedDao().insertFeed(Feed(title = "RSS", url = URL("https://rss.invalid/")))
            rss.feedItemDao().insertFeedItem(FeedItem(guid = "rss", feedId = localFeed, plainTitle = "RSS title"))
            repo.setCurrentFeedAndTag(localFeed, "")
            val counts = Channel<Int>(Channel.UNLIMITED)
            val collect = launch(Dispatchers.IO) { repo.getCurrentFeedListVisibleItemCount().collect { counts.send(it) } }
            assertEquals(1, withTimeout(5000) { counts.receive() })
            use(profile)
            withTimeout(5000) { repo.currentFeedAndTag.first { it.first == ID_ALL_FEEDS } }
            repo.setCurrentFeedAndTag(ID_SAVED_ARTICLES, "")
            withTimeout(5000) { while (counts.receive() != 0) {} }
            modes.commit(BackendSelection())
            withTimeout(5000) { repo.currentFeedAndTag.first { it.first == localFeed } }
            withTimeout(5000) { while (counts.receive() != 1) {} }
            collect.cancel()
            use(profile)
            assertEquals(BackendSelection(BackendMode.MINIFLUX, profile.id), BackendModeStore(prefs).selection.value)
            assertEquals(
                time,
                cache
                    .database(profile.id)
                    .cacheDao()
                    .profile()!!
                    .changedCheckpoint,
            )
        }

    @Test fun repositoryPagingHotSwitchesOfflineWithoutMixingRssRows() =
        runBlocking {
            val profile = prepare()
            val feed = rss.feedDao().insertFeed(Feed(title = "RSS Feed", url = URL("https://rss.invalid/")))
            rss.feedItemDao().insertFeedItem(FeedItem(guid = "rss", feedId = feed, plainTitle = "RSS title"))
            val differ =
                AsyncPagingDataDiffer(
                    diffCallback =
                        object : DiffUtil.ItemCallback<FeedListItem>() {
                            override fun areItemsTheSame(
                                a: FeedListItem,
                                b: FeedListItem,
                            ) = a.articleKey == b.articleKey

                            override fun areContentsTheSame(
                                a: FeedListItem,
                                b: FeedListItem,
                            ) = a == b
                        },
                    updateCallback =
                        object : ListUpdateCallback {
                            override fun onInserted(
                                position: Int,
                                count: Int,
                            ) {}

                            override fun onRemoved(
                                position: Int,
                                count: Int,
                            ) {}

                            override fun onMoved(
                                fromPosition: Int,
                                toPosition: Int,
                            ) {}

                            override fun onChanged(
                                position: Int,
                                count: Int,
                                payload: Any?,
                            ) {}
                        },
                )
            val job = launch(Dispatchers.Main) { repo.getCurrentFeedListItems().collectLatest { differ.submitData(it) } }

            suspend fun waitFor(title: String) = withTimeout(10000) { while (differ.itemCount == 0 || differ.peek(0)?.title != title) delay(10) }
            try {
                waitFor("RSS title")
                assertFalse(differ.peek(0)!!.articleKey.isMiniflux)
                use(profile)
                waitFor("Miniflux title")
                assertEquals(profile.id, differ.peek(0)!!.articleKey.profileId)
                rss.feedItemDao().insertFeedItem(FeedItem(guid = "background", feedId = feed, plainTitle = "RSS background update"))
                assertEquals(1, differ.itemCount)
                modes.commit(BackendSelection())
                withTimeout(10000) { while (differ.itemCount != 2) delay(10) }
                assertTrue((0 until differ.itemCount).all { differ.peek(it)?.articleKey?.isMiniflux == false })
            } finally {
                job.cancel()
            }
        }

    @Test fun collidingIdsRouteReaderAndOfflineMutationsToPinnedDatabaseOnly() =
        runBlocking {
            val profile = prepare()
            val key =
                ArticleKey(
                    cache
                        .database(profile.id)
                        .cacheDao()
                        .entryIdentity(profile.id, 9000)!!
                        .localFeedItemId,
                    profile.id,
                )
            val feed = rss.feedDao().insertFeed(Feed(title = "RSS Feed", url = URL("https://rss.invalid/")))
            rss.feedItemDao().insertFeedItem(FeedItem(id = key.localId, guid = "rss", feedId = feed, plainTitle = "RSS collision"))
            assertEquals("RSS collision", repo.getArticleFlow(ArticleKey(key.localId)).first()!!.title)
            assertEquals("Miniflux title", repo.getArticleFlow(key).first()!!.title)
            assertEquals("<p>Miniflux body</p>", repo.minifluxBody(key))
            use(profile)
            repo.markArticle(key, false)
            repo.bookmarkArticle(key, true)
            assertEquals(
                1,
                cache
                    .database(profile.id)
                    .cacheDao()
                    .pending(profile.id)
                    .size,
            )
            assertEquals(
                1,
                cache
                    .database(profile.id)
                    .cacheDao()
                    .starPending(profile.id)
                    .size,
            )
            assertNull(
                repo
                    .getArticleFlow(ArticleKey(key.localId))
                    .first()!!
                    .item!!
                    .readTime,
            )
            assertFalse(repo.getArticleFlow(ArticleKey(key.localId)).first()!!.bookmarked)
            modes.commit(BackendSelection())
            repo.markArticle(key, true)
            repo.bookmarkArticle(key, false)
            assertEquals(
                "unread",
                cache
                    .database(profile.id)
                    .cacheDao()
                    .pending(profile.id)
                    .single()
                    .desiredStatus,
            )
            assertFalse(
                cache
                    .database(profile.id)
                    .cacheDao()
                    .starPending(profile.id)
                    .single()
                    .desiredStarred,
            )
            repo.bookmarkArticle(ArticleKey(key.localId), true)
            assertTrue(repo.getArticleFlow(ArticleKey(key.localId)).first()!!.bookmarked)
            assertFalse(repo.getArticleFlow(key).first()!!.bookmarked)
            assertEquals(
                time,
                cache
                    .database(profile.id)
                    .cacheDao()
                    .profile()!!
                    .changedCheckpoint,
            )
            cache.close()
            cache = MinifluxCacheStore(context, suffix)
            assertEquals(
                1,
                cache
                    .database(profile.id)
                    .cacheDao()
                    .pending(profile.id)
                    .size,
            )
            assertEquals(
                1,
                cache
                    .database(profile.id)
                    .cacheDao()
                    .starPending(profile.id)
                    .size,
            )
            assertEquals("<p>Miniflux body</p>", cache.readBody(profile.id, key.localId))
        }

    @Test fun readerViewModelUsesCacheBodyAndMissingBodyDoesNotFetchRss() =
        runBlocking {
            val profile = prepare()
            val local =
                cache
                    .database(profile.id)
                    .cacheDao()
                    .entryIdentity(profile.id, 9000)!!
                    .localFeedItemId
            val store = ViewModelStore()
            val model =
                withContext(Dispatchers.Main) {
                    ArticleViewModel(di, SavedStateHandle(mapOf("itemId" to local, "profile" to profile.id))).also { store.put("reader", it) }
                }
            try {
                val state = withTimeout(10000) { model.viewState.first { it.articleTitle == "Miniflux title" && it.articleContent.elements.isNotEmpty() } }
                assertEquals(TextToDisplay.CONTENT, state.textToDisplay)
                assertEquals(ArticleKey(local, profile.id), model.articleKey)
                val previous = source.article(ArticleKey(local, profile.id)).first()!!
                cache.saveEntry(profile, MinifluxEntry(9000, 5000, "Miniflux title", "unread", false, time, time, "https://fixture.invalid/a", content = "<div>Miniflux body</div>"))
                val updated = source.article(ArticleKey(local, profile.id)).first()!!
                assertEquals(previous.item, updated.item)
                assertNotEquals(previous.contentRevision, updated.contentRevision)
                assertNotEquals(previous, updated)

                model.setBookmarked(true).join()
                assertTrue(withTimeout(5000) { model.viewState.first { it.isBookmarked } }.isBookmarked)
            } finally {
                withContext(Dispatchers.Main) { store.clear() }
            }
            cache.articleDirectory(profile.id).deleteRecursively()
            assertEquals("Miniflux title", source.article(ArticleKey(local, profile.id)).first()!!.title)
            assertNull(repo.minifluxBody(ArticleKey(local, profile.id)))
            assertEquals(1, cache.database(profile.id).cacheDao().entryCount())
        }
}
