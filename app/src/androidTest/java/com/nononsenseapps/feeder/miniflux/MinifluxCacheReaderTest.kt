package com.nononsenseapps.feeder.miniflux

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.blob.blobFile
import com.nononsenseapps.feeder.ui.compose.utils.WithAllPreviewProviders
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@RunWith(AndroidJUnit4::class)
class MinifluxCacheReaderTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val suffix = UUID.randomUUID().toString()
    private val root = File(app.cacheDir, "miniflux_reader_test_$suffix").apply { mkdirs() }
    private val context =
        object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this

            override fun getFilesDir(): File = root

            override fun getDatabasePath(name: String): File = File(root, "databases/$name").also { it.parentFile?.mkdirs() }
        }
    private val store = MinifluxCacheStore(context, "miniflux_reader_$suffix")
    private val server = MockWebServer().apply { start() }

    @After fun cleanup() {
        store.close()
        server.shutdown()
        root.deleteRecursively()
        app
            .getSharedPreferences("miniflux_reader_$suffix", 0)
            .edit()
            .clear()
            .commit()
    }

    private fun prepare(): MinifluxProfile =
        runBlocking {
            val profile = store.prepare(server.url("/").toString(), MinifluxAccount(42, "test"))
            val category = MinifluxCategory(99, "News")
            store.saveCategories(profile, listOf(category))
            store.saveFeed(profile, MinifluxFeed(5000, "Feed", server.url("/rss").toString(), category), category)
            store.saveEntry(
                profile,
                MinifluxEntry(
                    9000,
                    5000,
                    "Cached title",
                    "unread",
                    true,
                    "2026-10-23T00:00:00Z",
                    null,
                    server.url("/article").toString(),
                    content = "<p>Cached body</p><img src='${server.url("/rss")}'><iframe src='${server.url("/article")}'></iframe>",
                ),
            )
            profile
        }

    @Test fun existingReaderDisplaysCachedBodyWithoutMutationOrAutomaticNetwork() {
        val profile = prepare()
        store.close() // Simulate reopening the persisted cache.
        compose.setContent { WithAllPreviewProviders { MinifluxCachePreview(store, profile, {}) } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Cached title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Cached title").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Cached body", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("readerColumn").assertExists()
        runBlocking {
            val identity = store.database(profile.id).cacheDao().entryIdentity(profile.id, 9000)!!
            assertNotEquals(9000L, identity.localFeedItemId)
            assertEquals("unread", identity.remoteStatus)
            assertEquals(true, identity.remoteStarred)
            assertEquals(
                null,
                store
                    .database(profile.id)
                    .cacheDao()
                    .item(identity.localFeedItemId)!!
                    .readTime,
            )
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun missingBlobShowsErrorWithoutRssOrOriginalContentFallback() {
        val profile = prepare()
        runBlocking {
            val identity = store.database(profile.id).cacheDao().entryIdentity(profile.id, 9000)!!
            blobFile(identity.localFeedItemId, store.articleDirectory(profile.id)).delete()
        }
        compose.setContent { WithAllPreviewProviders { MinifluxCachePreview(store, profile, {}) } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Cached title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Cached title").performClick()
        val error = app.getString(R.string.miniflux_cache_body_missing)
        compose.waitUntil(10000) { compose.onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, server.requestCount)
    }

    @Test fun explicitReadUnreadShowsPendingAndConfirmationUpdatesOpenPreview() {
        val profile = prepare()
        compose.setContent { WithAllPreviewProviders { MinifluxCachePreview(store, profile, {}) } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Cached title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Cached title").performClick()
        val markRead = app.getString(R.string.miniflux_mark_read)
        val markUnread = app.getString(R.string.miniflux_mark_unread)
        val pending = app.getString(R.string.miniflux_read_pending)
        compose.onNodeWithText(markRead).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText(pending).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(markUnread).assertExists()
        runBlocking {
            val remote = MinifluxEntry(9000, 5000, "Cached title", "unread", true, "2026-10-23T00:00:00Z", null, server.url("/article").toString(), content = "<p>Cached body</p>")
            store.saveEntry(profile, remote)
        }
        compose.onNodeWithText(markUnread).assertExists()
        compose.onNodeWithText(pending).assertExists()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"id":42,"username":"test"}"""))
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(204))
        server.enqueue(
            okhttp3.mockwebserver.MockResponse().setBody(
                """{"id":9000,"feed_id":5000,"title":"Cached title","status":"read","starred":true,"published_at":"2026-10-23T00:00:00Z","url":"https://example.com/a"}""",
            ),
        )
        runBlocking { assertEquals(0, MinifluxReadSync(MinifluxApiClient(), store, MinifluxReadMutationStore(store)).sync(profile, "test-token")) }
        compose.waitUntil(10000) { compose.onAllNodesWithText(pending).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(markUnread).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText(pending).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(markRead).assertExists()
        compose.onNodeWithTag("readerColumn").assertExists()
        runBlocking { assertEquals("unread", store.articles(profile.id, 0).single().effectiveStatus) }
        assertEquals(3, server.requestCount)
    }
}
