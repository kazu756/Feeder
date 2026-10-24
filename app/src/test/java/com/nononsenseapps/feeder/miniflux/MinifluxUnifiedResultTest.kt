package com.nononsenseapps.feeder.miniflux

import org.junit.Test
import kotlin.test.assertEquals

class MinifluxUnifiedResultTest {
    @Test fun timeoutRetainsCommittedReadAndClosesRunningCache() {
        val original =
            MinifluxUnifiedSyncResult(
                read = MinifluxUnifiedStep(MinifluxUnifiedStepState.SUCCESS, MinifluxReadSyncResult(1, 1, 0, 0)),
                incremental = MinifluxUnifiedStep(MinifluxUnifiedStepState.RUNNING),
                starPending = 1,
            )
        val result = original.interrupted("TIMEOUT")
        assertEquals(MinifluxUnifiedOverall.PARTIAL_SUCCESS, result.overall)
        assertEquals(original.read, result.read)
        assertEquals(1, result.starPending)
        assertEquals(MinifluxUnifiedStepState.FAILED, result.incremental.state)
        assertEquals("TIMEOUT", result.incremental.errorType)
        assertEquals("CONTROLLER", result.timeoutSource)
        assertEquals(MinifluxUnifiedStepState.NOT_RUN, result.reconciliation.state)
    }

    @Test fun partialSenderAcknowledgementsRemainPartialAfterUnexpectedFailure() {
        val original = MinifluxUnifiedSyncResult(star = MinifluxUnifiedStep(MinifluxUnifiedStepState.RUNNING, MinifluxStarSyncResult(20, 3, 0, 17, 0, true)))
        val result = original.interrupted("LOCAL_ERROR")
        assertEquals(MinifluxUnifiedOverall.PARTIAL_SUCCESS, result.overall)
        assertEquals(original.star.value, result.star.value)
        assertEquals(MinifluxUnifiedStepState.FAILED, result.star.state)
    }

    @Test fun credentialsFailureBeforeWorkIsFailed() {
        assertEquals(MinifluxUnifiedOverall.FAILED, MinifluxUnifiedSyncResult().interrupted("MISSING_TOKEN").overall)
    }

    @Test fun cancellationPreservesEarlierResultsAndPendingCounts() {
        val original =
            MinifluxUnifiedSyncResult(
                read = MinifluxUnifiedStep(MinifluxUnifiedStepState.SUCCESS, MinifluxReadSyncResult(1, 1, 0, 0)),
                star = MinifluxUnifiedStep(MinifluxUnifiedStepState.RUNNING),
                readPending = 2,
                starPending = 1,
            )
        val result = original.interrupted("CANCELLED", cancelled = true)
        assertEquals(MinifluxUnifiedOverall.CANCELLED, result.overall)
        assertEquals(original.read, result.read)
        assertEquals(MinifluxUnifiedStepState.CANCELLED, result.star.state)
        assertEquals(2, result.readPending)
        assertEquals(1, result.starPending)
    }
}
