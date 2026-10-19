package com.nononsenseapps.feeder.commafeed

import com.nononsenseapps.feeder.db.room.CommaFeedArticleMapping
import com.nononsenseapps.feeder.db.room.CommaFeedArticleMappingDao
import com.nononsenseapps.feeder.db.room.FeedDao
import com.nononsenseapps.feeder.db.room.FeedItemDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance

class CommaFeedDiagnosticService(
    override val di: DI,
) : DIAware {
    private val client: CommaFeedRestClient by instance()
    private val localDao: FeedItemDao by instance()
    private val feedDao: FeedDao by instance()
    private val mappingDao: CommaFeedArticleMappingDao by instance()

    suspend fun run(
        baseUrl: String,
        username: String,
        password: String,
    ): DiagnosticResult =
        withContext(Dispatchers.IO) {
            val normalizedBaseUrl = client.normalizedBaseUrl(baseUrl).toString()
            // Different accounts on one server must never share article mappings.
            val serverKey = "$normalizedBaseUrl|${username.trim()}"
            val remote = client.fetch(normalizedBaseUrl, username, password)
            val local =
                if (remote.isEmpty()) {
                    emptyList()
                } else {
                    localDao.loadForCommaFeedDiagnostic(remote.map { it.feedUrl }.distinct())
                }
            val result =
                CommaFeedArticleMatcher.compare(local, remote).copy(
                    matchingFeeds = feedDao.countForCommaFeedDiagnostic(remote.map { it.feedUrl }.distinct()),
                )
            val previous = mappingDao.forServer(serverKey)
            val currentByLocal = result.matches.associateBy { it.local.id }
            val observedLocalIds = local.map { it.id }.toSet()
            val observedRemoteIds = remote.map { it.id }.toSet()
            // A prior pair is trusted only if the current diagnostic independently confirms it.
            previous
                .filter { old ->
                    (old.localArticleId in observedLocalIds || old.remoteArticleId in observedRemoteIds) &&
                        currentByLocal[old.localArticleId]?.remote?.id != old.remoteArticleId
                }.forEach { mappingDao.delete(serverKey, it.localArticleId) }
            val now = System.currentTimeMillis()
            for (match in result.matches) {
                val old =
                    previous.firstOrNull {
                        it.localArticleId == match.local.id &&
                            it.remoteArticleId == match.remote.id &&
                            it.localFeedId == match.local.feedId &&
                            it.remoteFeedId == match.remote.feedId
                    }
                mappingDao.upsert(
                    CommaFeedArticleMapping(
                        serverKey = serverKey,
                        localArticleId = match.local.id,
                        remoteArticleId = match.remote.id,
                        localFeedId = match.local.feedId,
                        remoteFeedId = match.remote.feedId,
                        matchType = match.type.name,
                        matchedAt = old?.matchedAt ?: now,
                        lastObservedLocalRead = match.local.read,
                        lastObservedRemoteRead = match.remote.read,
                        lastObservedAt = now,
                        baselineRead = null,
                        pendingDesiredRead = null,
                    ),
                )
            }
            result
        }
}
