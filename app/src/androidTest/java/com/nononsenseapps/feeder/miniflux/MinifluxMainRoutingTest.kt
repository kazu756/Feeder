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
import com.nononsenseapps.feeder.ApplicationCoroutineScope
import com.nononsenseapps.feeder.FeederApplication
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.ArticleKey
import com.nononsenseapps.feeder.archmodel.BackendMode
import com.nononsenseapps.feeder.archmodel.BackendModeStore
import com.nononsenseapps.feeder.archmodel.BackendSelection
import com.nononsenseapps.feeder.archmodel.FeedItemStore
import com.nononsenseapps.feeder.archmodel.FeedStore
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.archmodel.SettingsStore
import com.nononsenseapps.feeder.archmodel.TextToDisplay
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
import com.nononsenseapps.feeder.ui.MainActivityViewModel
import com.nononsenseapps.feeder.ui.compose.feed.FeedListItem
import com.nononsenseapps.feeder.ui.compose.feedarticle.ArticleViewModel
import com.nononsenseapps.feeder.ui.compose.feedarticle.FeedListFilter
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
