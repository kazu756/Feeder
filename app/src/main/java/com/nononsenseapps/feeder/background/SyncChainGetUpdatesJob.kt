package com.nononsenseapps.feeder.background

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.getSystemService
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.sync.SyncRestClient
import com.nononsenseapps.feeder.util.logDebug
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.instance

class SyncChainGetUpdatesJob(
    context: Context,
    override val params: JobParameters,
) : BackgroundJob,
    DIAware {
    override val di: DI by closestDI(context)

    private val syncClient: SyncRestClient by instance()
    private val repository: Repository by instance()

    private val backendSync: BackendSyncCoordinator by instance()

    override val jobId: Int = params.jobId

    override suspend fun doWork() {
        backendSync.withCurrentRun { selected ->
            if (selected.mode != com.nononsenseapps.feeder.archmodel.BackendMode.LOCAL_RSS) return@withCurrentRun

            try {
                logDebug(LOG_TAG, "Doing work")
                syncClient.getRead()
                repository.applyRemoteReadMarks()
                syncClient.getDevices()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error when getting updates", e)
            }
        }
    }

    companion object {
        const val LOG_TAG = "FEEDER_GETUPDATES"
    }
}

fun runOnceSyncChainGetUpdates(di: DI) {
    val modes: com.nononsenseapps.feeder.archmodel.BackendModeStore by di.instance()
    if (modes.selection.value.mode != com.nononsenseapps.feeder.archmodel.BackendMode.LOCAL_RSS) return
    val repository: Repository by di.instance()
    val context: Application by di.instance()

    if (!repository.isSyncChainConfigured) {
        logDebug(SyncChainGetUpdatesJob.LOG_TAG, "Sync chain not enabled")
        return
    }

    val jobScheduler: JobScheduler? = context.getSystemService()

    if (jobScheduler == null) {
        Log.e(SyncChainGetUpdatesJob.LOG_TAG, "JobScheduler not available")
        return
    }

    val componentName = ComponentName(context, FeederJobService::class.java)
    val jobInfo =
        JobInfo
            .Builder(BackgroundJobId.SYNC_CHAIN_GET_UPDATES.jobId, componentName)
            .setRequiresCharging(repository.syncOnlyWhenCharging.value)
            .setRequiredNetworkType(
                if (repository.syncOnlyOnWifi.value) {
                    JobInfo.NETWORK_TYPE_UNMETERED
                } else {
                    JobInfo.NETWORK_TYPE_ANY
                },
            ).build()

    jobScheduler.schedule(jobInfo)
}
