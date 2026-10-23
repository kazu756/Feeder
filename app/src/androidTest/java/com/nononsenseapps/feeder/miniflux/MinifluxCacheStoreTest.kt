package com.nononsenseapps.feeder.miniflux

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nononsenseapps.feeder.blob.blobFile
import com.nononsenseapps.feeder.db.room.AppDatabase
import com.nononsenseapps.feeder.db.room.CommaFeedArticleMapping
import com.nononsenseapps.feeder.db.room.Feed
import com.nononsenseapps.feeder.db.room.FeedItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class MinifluxCacheStoreTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val suffix = UUID.randomUUID().toString()
    private val root = File(app.cacheDir, "miniflux_test_$suffix").apply { mkdirs() }
    private val context =
        object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this

            override fun getFilesDir(): File = root

            override fun getDatabasePath(name: String): File = File(root, "databases/$name").also { it.parentFile?.mkdirs() }
        }
    private val store = MinifluxCacheStore(context, "miniflux_test_$suffix")
    private val entry = MinifluxEntry(9000, 5000, "Title", "unread", true, "2026-10-23T00:00:00Z", "2026-10-23T00:00:01Z", "https://example.com/a", "Author", "<p>Cached body</p>")

    @After fun cleanup() {
        store.close()
        // This UUID-owned test directory contains no app data.
        root.deleteRecursively()
        app
            .getSharedPreferences("miniflux_test_$suffix", 0)
            .edit()
            .clear()
            .commit()
    }

    private suspend fun prepare(): MinifluxProfile {
        val profile = store.prepare("https://example.com", MinifluxAccount(42, "test"))
        store.saveCategories(profile, listOf(MinifluxCategory(99, "News")))
        store.saveFeed(profile, MinifluxFeed(5000, "Feed", "https://example.com/rss", MinifluxCategory(99, "News")), MinifluxCategory(99, "News"))
        return profile
    }

    @Test fun remoteMappingsRemainStableAndBodyRepairsAfterDeletion() =
        runBlocking<Unit> {
            val profile = prepare()
            store.saveEntry(profile, entry)
            val dao = store.database(profile.id).cacheDao()
            val first = dao.entryIdentity(profile.id, entry.id)!!
            assertNotEquals(entry.id, first.localFeedItemId)
            assertNotEquals(entry.feedId, dao.feedIdentity(profile.id, entry.feedId)!!.localFeedId)
            assertEquals(entry.content, store.readBody(profile.id, first.localFeedItemId))
            store.saveEntry(profile, entry.copy(title = "Updated", content = "<p>Updated body</p>"))
            assertEquals(first.localFeedItemId, dao.entryIdentity(profile.id, entry.id)!!.localFeedItemId)
            assertEquals(1, dao.entryCount())
            assertEquals("<p>Updated body</p>", store.readBody(profile.id, first.localFeedItemId))
            blobFile(first.localFeedItemId, store.articleDirectory(profile.id)).delete()
            assertFails { store.readBody(profile.id, first.localFeedItemId) }
            store.saveEntry(profile, entry)
            assertEquals(entry.content, store.readBody(profile.id, first.localFeedItemId))
            assertEquals("miniflux:${profile.id}:${entry.id}", dao.item(first.localFeedItemId)!!.guid)
        }

    @Test fun categoryRenamePreservesIdentityAndLocalOptions() =
        runBlocking<Unit> {
            val profile = prepare()
            val dao = store.database(profile.id).cacheDao()
            val identity = dao.feedIdentity(profile.id, 5000)!!
            dao.updateFeed(dao.feed(identity.localFeedId)!!.copy(summarizeOnOpen = true, fetchOgImages = true))
            val renamed = MinifluxCategory(99, "Renamed")
            store.saveCategories(profile, listOf(renamed))
            store.saveFeed(profile, MinifluxFeed(5000, "New title", "https://example.com/new", renamed), renamed)
            val after = dao.feedIdentity(profile.id, 5000)!!
            assertEquals(identity.localFeedId, after.localFeedId)
            assertEquals(99L, after.remoteCategoryId)
            assertEquals("Renamed", dao.feed(after.localFeedId)!!.tag)
            assertTrue(dao.feed(after.localFeedId)!!.summarizeOnOpen)
            assertTrue(dao.feed(after.localFeedId)!!.fetchOgImages)
        }

    @Test fun profilesHaveIndependentDatabaseIdsAndBlobNamespaces() =
        runBlocking<Unit> {
            val first = prepare()
            store.saveEntry(first, entry)
            val other = store.prepare("https://example.com", MinifluxAccount(43, "other"))
            store.saveCategories(other, listOf(MinifluxCategory(99, "News")))
            store.saveFeed(other, MinifluxFeed(5000, "Feed", "https://example.com/rss"), MinifluxCategory(99, "News"))
            store.saveEntry(other, entry.copy(content = "Other account"))
            assertNotEquals(first.id, other.id)
            assertNotEquals(store.articleDirectory(first.id), store.articleDirectory(other.id))
            assertEquals(
                entry.content,
                store.readBody(
                    first.id,
                    store
                        .database(first.id)
                        .cacheDao()
                        .entryIdentity(first.id, 9000)!!
                        .localFeedItemId,
                ),
            )
            assertEquals(1, store.summary(first).blobs)
            assertEquals(1, store.summary(other).blobs)
        }

    @Test fun partialFailureKeepsGoodCacheAndCanRetry() =
        runBlocking<Unit> {
            val profile = prepare()
            store.saveEntry(profile, entry)
            val bad = entry.copy(id = 9001)
            val directory = store.articleDirectory(profile.id)
            val staging = File(directory.parentFile, "staging")
            staging.deleteRecursively()
            staging.writeText("prevent directory creation")
            assertFails { store.saveEntry(profile, bad) }
            assertEquals(
                entry.content,
                store.readBody(
                    profile.id,
                    store
                        .database(profile.id)
                        .cacheDao()
                        .entryIdentity(profile.id, 9000)!!
                        .localFeedItemId,
                ),
            )
            assertFalse(
                store
                    .database(profile.id)
                    .cacheDao()
                    .entryIdentity(profile.id, 9001)!!
                    .blobReady,
            )
            assertEquals(null, store.selectedProfile()!!.lastSuccessfulSync)
            staging.delete()
            store.saveEntry(profile, bad)
            store.complete(profile)
            assertEquals(2, store.summary(profile).blobs)
            assertTrue(store.selectedProfile()!!.lastSuccessfulSync != null)
        }

    @Test fun normalDatabaseAndCommaFeedMappingAreUntouched() =
        runBlocking<Unit> {
            val local = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
            try {
                val feedId = local.feedDao().insertFeed(Feed(title = "Local", url = URL("https://example.com/rss")))
                val itemId = local.feedItemDao().insertFeedItem(FeedItem(guid = "local", feedId = feedId, readTime = Instant.EPOCH))
                val mapping = CommaFeedArticleMapping("old-server", itemId, "remote", feedId, "feed", "GUID", 1, true, true, 1)
                local.commaFeedArticleMappingDao().upsert(mapping)
                val profile = prepare()
                store.saveEntry(profile, entry)
                assertEquals("Local", local.feedDao().getFeed(feedId)!!.title)
                assertEquals(Instant.EPOCH, local.feedItemDao().loadFeedItem(itemId)!!.readTime)
                assertEquals(mapping, local.commaFeedArticleMappingDao().forServer("old-server").single())
                assertEquals(40, local.openHelper.readableDatabase.version)
            } finally {
                local.close()
            }
        }

    @Test fun schemaConstraintsAndReopenPreserveCache() =
        runBlocking<Unit> {
            val profile = prepare()
            store.saveEntry(profile, entry)
            val db = store.database(profile.id)
            val dao = db.cacheDao()
            val identity = dao.entryIdentity(profile.id, entry.id)!!
            assertFails { dao.saveEntryIdentity(identity.copy(remoteEntryId = 9001)) }
            assertFails { dao.saveEntryIdentity(identity.copy(remoteFeedId = 99999)) }
            db.openHelper.readableDatabase
                .query("PRAGMA foreign_key_check")
                .use { assertEquals(0, it.count) }
            assertEquals(1, db.openHelper.readableDatabase.version)
            store.close()
            assertEquals(entry.content, store.readBody(profile.id, identity.localFeedItemId))
        }

    @Test fun endToEndSyncIsGetOnlyAndRetainsCompletionOnFailure() =
        runBlocking<Unit> {
            val server = okhttp3.mockwebserver.MockWebServer()
            server.start()
            var fail = false
            server.dispatcher =
                object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                        assertEquals("GET", request.method)
                        assertEquals("m2-test-token", request.getHeader("X-Auth-Token"))
                        val body =
                            when (request.requestUrl!!.encodedPath) {
                                "/v1/me" -> """{"id":42,"username":"test"}"""
                                "/v1/feeds" -> """[{"id":5000,"title":"Feed","feed_url":"https://example.com/rss","category":{"id":99,"title":"News"}}]"""
                                "/v1/categories" -> """[{"id":99,"title":"News"}]"""
                                "/v1/entries" -> {
                                    if (fail) return okhttp3.mockwebserver.MockResponse().setResponseCode(503)
                                    assertEquals("100", request.requestUrl!!.queryParameter("limit"))
                                    if (request.requestUrl!!.queryParameter("before_entry_id") != null || request.requestUrl!!.queryParameter("status") == "read") {
                                        """{"total":0,"entries":[]}"""
                                    } else {
                                        """
{"total":1,
"entries":[{"id":9000,
"feed_id":5000,
"title":"Title",
"status":"unread",
"starred":true,
"published_at":"2026-10-23T00:00:00Z",
"url":"https://example.com/a",
"content":"<p>Cached body</p>"}]}
"""
                                    }
                                }
                                else -> return okhttp3.mockwebserver.MockResponse().setResponseCode(404)
                            }
                        return okhttp3.mockwebserver.MockResponse().setBody(body)
                    }
                }
            try {
                val sync = MinifluxCacheSync(MinifluxApiClient(), store)
                val first = sync.sync(server.url("/").toString(), "m2-test-token") { _, _ -> }
                assertEquals(1, first.entries)
                assertEquals(1, first.blobs)
                val localId =
                    store
                        .database(first.profile.id)
                        .cacheDao()
                        .entryIdentity(first.profile.id, 9000)!!
                        .localFeedItemId
                val again = sync.sync(server.url("/").toString(), "m2-test-token") { _, _ -> }
                assertEquals(1, again.entries)
                assertEquals(
                    localId,
                    store
                        .database(first.profile.id)
                        .cacheDao()
                        .entryIdentity(first.profile.id, 9000)!!
                        .localFeedItemId,
                )
                val success = store.selectedProfile()!!.lastSuccessfulSync
                fail = true
                assertFails { sync.sync(server.url("/").toString(), "m2-test-token") { _, _ -> } }
                assertEquals(success, store.selectedProfile()!!.lastSuccessfulSync)
                assertEquals("<p>Cached body</p>", store.readBody(first.profile.id, localId))
            } finally {
                server.shutdown()
            }
        }
}
