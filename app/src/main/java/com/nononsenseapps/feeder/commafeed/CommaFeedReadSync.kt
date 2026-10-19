package com.nononsenseapps.feeder.commafeed

import com.nononsenseapps.feeder.archmodel.FeedItemStore
import com.nononsenseapps.feeder.db.room.FeedItemDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance

data class ReadSyncPlan(
    val localIds: List<Long>,
    val remoteIds: List<String>,
    val alreadySame: Int,
    val unmarkableRemote: Int,
)

fun planReadSync(diagnostic: DiagnosticResult): ReadSyncPlan =
    ReadSyncPlan(
        localIds = diagnostic.matches.filter { !it.local.read && it.remote.read }.map { it.local.id },
        remoteIds = diagnostic.matches.filter { it.local.read && !it.remote.read && it.remote.markable }.map { it.remote.id },
        alreadySame = diagnostic.matches.count { it.local.read == it.remote.read },
        unmarkableRemote = diagnostic.matches.count { it.local.read && !it.remote.read && !it.remote.markable },
    )

data class ReadSyncResult(
    val remoteFetched: Int,
    val localConsidered: Int,
    val matched: Int,
    val alreadySame: Int,
    val localMarkedRead: Int,
    val remoteMarkedRead: Int,
    val ambiguousLocal: Int,
    val ambiguousRemote: Int,
    val unmatchedLocal: Int,
    val unmatchedRemote: Int,
    val localFailedWrites: Int,
    val remoteFailedWrites: Int,
    val remoteVerificationFailed: Boolean,
)

class CommaFeedReadSyncService(
    override val di: DI,
) : DIAware {
    private val client: CommaFeedRestClient by instance()
    private val localDao: FeedItemDao by instance()
    private val itemStore: FeedItemStore by instance()

    suspend fun run(
        baseUrl: String,
        username: String,
        password: String,
    ): ReadSyncResult =
        withContext(Dispatchers.IO) {
            val normalizedBaseUrl = client.normalizedBaseUrl(baseUrl).toString()
            val remote = client.fetch(normalizedBaseUrl, username, password)
            val local =
                if (remote.isEmpty()) emptyList() else localDao.loadForCommaFeedDiagnostic(remote.map { it.feedUrl }.distinct())
            val diagnostic = CommaFeedArticleMatcher.compare(local, remote)
            val plan = planReadSync(diagnostic)

            var postedRemoteIds = emptySet<String>()
            var remoteFailed = plan.unmarkableRemote
            for (ids in plan.remoteIds.chunked(100)) {
                try {
                    client.markRead(normalizedBaseUrl, username, password, ids)
                    postedRemoteIds = postedRemoteIds + ids
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    remoteFailed += ids.size
                }
            }

            var remoteMarked = postedRemoteIds.size
            var verificationFailed = false
            if (postedRemoteIds.isNotEmpty()) {
                try {
                    val confirmed =
                        client
                            .fetch(normalizedBaseUrl, username, password)
                            .filter { it.id in postedRemoteIds && it.read }
                            .map { it.id }
                            .toSet()
                    remoteMarked = confirmed.size
                    remoteFailed += postedRemoteIds.size - confirmed.size
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    verificationFailed = true
                }
            }

            var localMarked = 0
            var localFailed = 0
            for (id in plan.localIds) {
                try {
                    itemStore.markAsReadAndNotified(id)
                    localMarked++
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    localFailed++
                }
            }

            ReadSyncResult(
                remoteFetched = diagnostic.remoteFetched,
                localConsidered = diagnostic.localConsidered,
                matched = diagnostic.matchedTotal,
                alreadySame = plan.alreadySame,
                localMarkedRead = localMarked,
                remoteMarkedRead = remoteMarked,
                ambiguousLocal = diagnostic.ambiguousLocal,
                ambiguousRemote = diagnostic.ambiguousRemote,
                unmatchedLocal = diagnostic.unmatchedLocal,
                unmatchedRemote = diagnostic.unmatchedRemote,
                localFailedWrites = localFailed,
                remoteFailedWrites = remoteFailed,
                remoteVerificationFailed = verificationFailed,
            )
        }
}
