package com.nononsenseapps.feeder.db.room

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nononsenseapps.feeder.ui.TestDatabaseRule
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class CommaFeedArticleMappingDaoTest {
    @get:Rule
    val testDb = TestDatabaseRule(ApplicationProvider.getApplicationContext())

    @Test
    fun insertUpdateServerIsolationAndStaleDeletion() =
        runBlocking {
            val db = testDb.db
            val feedId = db.feedDao().insertFeed(Feed(title = "Feed", url = URL("https://example.com/feed")))
            val localId = db.feedItemDao().insertFeedItem(FeedItem(guid = "guid", plainTitle = "Article", feedId = feedId))
            val dao = db.commaFeedArticleMappingDao()
            val mapping =
                CommaFeedArticleMapping(
                    serverKey = "https://server-a/",
                    localArticleId = localId,
                    remoteArticleId = "remote-1",
                    localFeedId = feedId,
                    remoteFeedId = "feed-1",
                    matchType = "GUID",
                    matchedAt = 1,
                    lastObservedLocalRead = false,
                    lastObservedRemoteRead = true,
                    lastObservedAt = 1,
                )
            dao.upsert(mapping)
            dao.upsert(mapping.copy(serverKey = "https://server-b/"))
            dao.upsert(mapping.copy(lastObservedAt = 2, lastObservedRemoteRead = false))
            assertEquals(2, dao.forServer("https://server-a/").single().lastObservedAt)
            assertEquals(false, dao.forServer("https://server-a/").single().lastObservedRemoteRead)
            assertEquals(1, dao.forServer("https://server-b/").size)
            dao.delete("https://server-a/", localId)
            assertEquals(0, dao.forServer("https://server-a/").size)
            assertEquals(1, dao.forServer("https://server-b/").size)
        }
}
