package com.nononsenseapps.feeder.ui.compose.feedarticle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleEdgePullTest {
    private val state = ArticleEdgePullState(thresholdPx = 40f)

    @Test
    fun topPullOnlyStartsAtTop() {
        assertEquals(0f, state.onPull(100f, canPullFromTop = false, canPullFromBottom = false))
        assertFalse(state.hasPull)
        assertNull(state.onRelease(topEnabled = true, bottomEnabled = true))
    }

    @Test
    fun topPullBelowThresholdDoesNotTrigger() {
        state.onPull(79f, canPullFromTop = true, canPullFromBottom = false)

        assertNull(state.onRelease(topEnabled = true, bottomEnabled = true))
    }

    @Test
    fun topPullAtThresholdTriggersOnce() {
        state.onPull(80f, canPullFromTop = true, canPullFromBottom = false)

        assertEquals(ArticleEdgePullAction.RequestFullText, state.onRelease(topEnabled = true, bottomEnabled = true))
        assertNull(state.onRelease(topEnabled = true, bottomEnabled = true))
    }

    @Test
    fun reverseDragReducesTopPull() {
        state.onPull(100f, canPullFromTop = true, canPullFromBottom = false)
        state.onPull(-30f, canPullFromTop = true, canPullFromBottom = false)

        assertEquals(70f, state.distancePulled)
        assertFalse(state.isArmed)
    }

    @Test
    fun requestingFullTextPreventsAnotherTopAction() {
        state.updateRequestingFullText(true)
        state.onPull(100f, canPullFromTop = true, canPullFromBottom = false)

        assertFalse(state.hasPull)
        assertNull(state.onRelease(topEnabled = true, bottomEnabled = true))
    }

    @Test
    fun bottomPullOnlyStartsAtBottom() {
        assertEquals(0f, state.onPull(-100f, canPullFromTop = false, canPullFromBottom = false))
        assertFalse(state.hasPull)
    }

    @Test
    fun bottomPullBelowThresholdDoesNotTrigger() {
        state.onPull(-79f, canPullFromTop = false, canPullFromBottom = true)

        assertNull(state.onRelease(topEnabled = true, bottomEnabled = true))
    }

    @Test
    fun bottomPullAtThresholdTriggersOnce() {
        state.onPull(-80f, canPullFromTop = false, canPullFromBottom = true)

        assertEquals(ArticleEdgePullAction.OpenWebPage, state.onRelease(topEnabled = true, bottomEnabled = true))
        assertNull(state.onRelease(topEnabled = true, bottomEnabled = true))
    }

    @Test
    fun disabledBottomActionDoesNotTrigger() {
        state.onPull(-80f, canPullFromTop = false, canPullFromBottom = true)

        assertNull(state.onRelease(topEnabled = true, bottomEnabled = false))
        assertTrue(!state.hasPull)
    }
}
