package com.nononsenseapps.feeder.background

import com.nononsenseapps.feeder.archmodel.BackendMode
import com.nononsenseapps.feeder.archmodel.BackendSelection
import com.nononsenseapps.feeder.miniflux.MinifluxSyncRunner
import com.nononsenseapps.feeder.miniflux.MinifluxUnifiedOverall
import com.nononsenseapps.feeder.miniflux.MinifluxUnifiedSyncResult
import com.nononsenseapps.feeder.model.RssLocalSync
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.instance
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackendSyncCoordinatorTest {
    private val local = BackendSelection()
    private val mini = BackendSelection(BackendMode.MINIFLUX, "a".repeat(64))
    private var selected = local
    private val coordinator = BackendSyncCoordinator { selected }
    private val runner = mockk<MinifluxSyncRunner>()
    private val dispatcher = BackendSyncDispatcher(coordinator, runner)

    @Before fun setup() {
        mockkStatic("com.nononsenseapps.feeder.util.LoggingKt")
        justRun {
            com.nononsenseapps.feeder.util
                .logDebug(any(), any(), any())
        }
        coEvery { runner.sync(any()) } returns MinifluxUnifiedSyncResult(overall = MinifluxUnifiedOverall.SUCCESS)
    }

    @After fun cleanup() {
        unmockkStatic("com.nononsenseapps.feeder.util.LoggingKt")
    }

    @Test fun localScheduledRunUsesOnlyRss() =
        runBlocking {
            var rss = 0
            dispatcher.sync(local) {
                rss++
                assertTrue(coordinator.allowsLocalRss())
            }
            assertEquals(1, rss)
            coVerify(exactly = 0) { runner.sync(any()) }
        }

    @Test fun minifluxScheduledRunUsesOnlyPinnedMiniflux() =
        runBlocking {
            selected = mini
            var rss = 0
            dispatcher.sync(selected) { rss++ }
            assertEquals(0, rss)
            coVerify(exactly = 1) { runner.sync(mini) }
        }

    @Test fun nextRunsFollowBothSwitchDirectionsWithoutChangingRegistration() =
        runBlocking {
            var rss = 0
            dispatcher.sync(selected) { rss++ }
            selected = mini
            dispatcher.sync(selected) { rss++ }
            selected = local
            dispatcher.sync(selected) { rss++ }
            assertEquals(2, rss)
            coVerify(exactly = 1) { runner.sync(mini) }
        }

    @Test fun activeRssRunKeepsSnapshotAcrossNestedCallsAndDispatcherChange() =
        runBlocking {
            dispatcher.sync(local) {
                selected = mini
                assertTrue(coordinator.allowsLocalRss())
                coordinator.withCurrentRun { assertEquals(local, it) }
                coordinator.withRun(mini) { assertEquals(local, it) }
            }
            assertFalse(coordinator.allowsLocalRss())
            dispatcher.sync(selected) { error("RSS after switch") }
            coVerify(exactly = 1) { runner.sync(mini) }
        }

    @Test fun nestedRssAndChainScopesDoNotDeadlock() =
        runBlocking {
            withTimeout(1000) { coordinator.withCurrentRun { coordinator.withCurrentRun { assertTrue(coordinator.allowsLocalRss()) } } }
        }

    @Test fun boundarySerializesOldRssAndNewMinifluxRun() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val old =
                launch {
                    coordinator.withRun(local) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            entered.await()
            selected = mini
            var miniEntered = false
            val next =
                launch {
                    coordinator.withCurrentRun {
                        miniEntered = true
                        assertEquals(mini, it)
                    }
                }
            delay(25)
            assertFalse(miniEntered)
            release.complete(Unit)
            old.join()
            next.join()
            assertTrue(miniEntered)
        }

    @Test fun cancellationReleasesPipelineForNextBackend() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val job =
                launch {
                    coordinator.withRun(local) {
                        entered.complete(Unit)
                        delay(10000)
                    }
                }
            entered.await()
            job.cancelAndJoin()
            selected = mini
            withTimeout(1000) { coordinator.withCurrentRun { assertEquals(mini, it) } }
        }

    @Test fun failedMinifluxRunNeverFallsBackToRss() =
        runBlocking {
            for (code in listOf("CONNECTION", "UNAUTHORIZED", "HTTP_500", "TIMEOUT", "LOCAL_ERROR")) {
                coEvery { runner.sync(mini) } returns MinifluxUnifiedSyncResult(overall = MinifluxUnifiedOverall.FAILED, errorType = code)
                dispatcher.sync(mini) { error("Automatic RSS fallback") }
            }
            coVerify(exactly = 5) { runner.sync(mini) }
        }

    @Test fun directWidgetAndInternalRssEntrySkipBeforeAnyRssDependency() =
        runBlocking {
            selected = mini
            val di = DI { bind<BackendSyncCoordinator>() with instance(coordinator) }
            val rss = RssLocalSync(di)
            assertFalse(rss.syncFeeds(debugReallyForceNetwork = true))
            assertFalse(rss.syncFeeds(maxFeedItemCount = 500))
        }

    @Test fun queuedSnapshotIsFixedEvenWhenSelectionChangesAgain() =
        runBlocking {
            selected = mini
            coordinator.withRun(mini) {
                selected = local
                assertEquals(mini, coordinator.currentSelection())
                assertFalse(coordinator.allowsLocalRss())
            }
            assertTrue(coordinator.allowsLocalRss())
        }
}
