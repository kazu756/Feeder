package com.nononsenseapps.feeder.background

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import com.nononsenseapps.feeder.archmodel.BackendMode
import com.nononsenseapps.feeder.archmodel.BackendModeStore
import com.nononsenseapps.feeder.archmodel.BackendSelection
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.archmodel.SyncFrequency
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.instance
import java.time.Duration

class BackendJobSchedulingTest {
    private val repository = mockk<Repository>()
    private val application = mockk<Application>(relaxed = true)
    private val scheduler = mockk<JobScheduler>(relaxed = true)
    private val info = mockk<JobInfo>()
    private val modes = mockk<BackendModeStore>()
    private val selection = MutableStateFlow(BackendSelection())
    private val frequency = MutableStateFlow(SyncFrequency.EVERY_3_HOURS)
    private val wifi = MutableStateFlow(true)
    private val charging = MutableStateFlow(true)
    private val di =
        DI {
            bind<Repository>() with instance(repository)
            bind<Application>() with instance(application)
            bind<JobScheduler>() with instance(scheduler)
            bind<BackendModeStore>() with instance(modes)
        }

    @Before fun setup() {
        every { modes.selection } returns selection
        every { repository.syncFrequency } returns frequency
        every { repository.syncOnlyOnWifi } returns wifi
        every { repository.syncOnlyWhenCharging } returns charging
        every { scheduler.allPendingJobs } returns emptyList()
        every { info.id } returns BackgroundJobId.RSS_SYNC_PERIODIC.jobId
        mockkConstructor(JobInfo.Builder::class)
        every { anyConstructed<JobInfo.Builder>().setRequiresCharging(any()) } answers { self as JobInfo.Builder }
        every { anyConstructed<JobInfo.Builder>().setRequiredNetworkType(any()) } answers { self as JobInfo.Builder }
        every { anyConstructed<JobInfo.Builder>().setPeriodic(any()) } answers { self as JobInfo.Builder }
        every { anyConstructed<JobInfo.Builder>().setPersisted(any()) } answers { self as JobInfo.Builder }
        every { anyConstructed<JobInfo.Builder>().build() } returns info
    }

    @After fun cleanup() {
        unmockkConstructor(JobInfo.Builder::class)
    }

    @Test fun bootPersistentRegistrationKeepsCadenceAndConstraintsInBothModes() {
        schedulePeriodicRssSync(di, replace = false)
        selection.value = BackendSelection(BackendMode.MINIFLUX, "a".repeat(64))
        schedulePeriodicRssSync(di, replace = false)
        verify(exactly = 2) { anyConstructed<JobInfo.Builder>().setPeriodic(Duration.ofHours(3).toMillis()) }
        verify(exactly = 2) { anyConstructed<JobInfo.Builder>().setPersisted(true) }
        verify(exactly = 2) { anyConstructed<JobInfo.Builder>().setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED) }
        verify(exactly = 2) { anyConstructed<JobInfo.Builder>().setRequiresCharging(true) }
        verify(exactly = 2) { scheduler.schedule(info) }
        verify(exactly = 0) { scheduler.cancel(any()) }
    }

    @Test fun existingRegistrationSurvivesBothSwitchDirectionsWithoutRescheduling() {
        every { scheduler.allPendingJobs } returns listOf(info)
        selection.value = BackendSelection(BackendMode.MINIFLUX, "a".repeat(64))
        schedulePeriodicRssSync(di, replace = false)
        selection.value = BackendSelection()
        schedulePeriodicRssSync(di, replace = false)
        verify(exactly = 0) { scheduler.schedule(any()) }
        verify(exactly = 0) { scheduler.cancel(any()) }
    }

    @Test fun manualFrequencyStillDisablesPeriodicSyncInMiniflux() {
        selection.value = BackendSelection(BackendMode.MINIFLUX, "a".repeat(64))
        frequency.value = SyncFrequency.MANUAL
        schedulePeriodicRssSync(di, replace = true)
        verify(exactly = 1) { scheduler.cancel(BackgroundJobId.RSS_SYNC_PERIODIC.jobId) }
        verify(exactly = 0) { scheduler.schedule(any()) }
    }

    @Test fun userNetworkChargingAndIntervalSettingsArePreserved() {
        wifi.value = false
        charging.value = false
        frequency.value = SyncFrequency.EVERY_30_MIN
        schedulePeriodicRssSync(di, replace = true)
        verify { anyConstructed<JobInfo.Builder>().setPeriodic(Duration.ofMinutes(30).toMillis()) }
        verify { anyConstructed<JobInfo.Builder>().setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY) }
        verify { anyConstructed<JobInfo.Builder>().setRequiresCharging(false) }
        verify(exactly = 1) { scheduler.schedule(info) }
    }
}
