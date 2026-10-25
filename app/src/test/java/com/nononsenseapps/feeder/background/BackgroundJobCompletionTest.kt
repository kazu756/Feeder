package com.nononsenseapps.feeder.background

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackgroundJobCompletionTest {
    @Test fun normalAndCancelledRunsCompleteOnceAndLeaveNoProcessingEntry() =
        runBlocking {
            val jobs = ConcurrentHashMap<Int, Job>()
            var finished = 0
            val normal = launch(start = CoroutineStart.LAZY) {}
            trackBackgroundJob(jobs, 1, normal) { finished++ }
            normal.start()
            normal.join()
            assertEquals(1, finished)
            assertTrue(jobs.isEmpty())
            val cancelled = launch(start = CoroutineStart.LAZY) { delay(10000) }
            trackBackgroundJob(jobs, 2, cancelled) { finished++ }
            cancelled.start()
            cancelled.cancelAndJoin()
            assertEquals(2, finished)
            assertTrue(jobs.isEmpty())
        }

    @Test fun alreadyCancelledScopeStillCompletesAndReleasesRegisteredJob() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob())
            scope.cancel()
            val work = scope.launch(start = CoroutineStart.LAZY) { error("Must not execute") }
            val jobs = ConcurrentHashMap<Int, Job>()
            var finished = 0
            trackBackgroundJob(jobs, 1, work) { finished++ }
            work.start()
            work.join()
            assertEquals(1, finished)
            assertTrue(jobs.isEmpty())
        }

    @Test fun finishingOlderRunCannotRemoveNewerRunWithSameId() =
        runBlocking {
            val jobs = ConcurrentHashMap<Int, Job>()
            val old = Job()
            val next = Job()
            var finished = 0
            trackBackgroundJob(jobs, 1, old) { finished++ }
            trackBackgroundJob(jobs, 1, next) { finished++ }
            old.complete()
            assertEquals(next, jobs[1])
            next.cancelAndJoin()
            assertTrue(jobs.isEmpty())
            assertEquals(2, finished)
        }
}
