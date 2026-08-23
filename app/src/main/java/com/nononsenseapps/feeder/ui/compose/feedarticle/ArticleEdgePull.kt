package com.nononsenseapps.feeder.ui.compose.feedarticle

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R

internal enum class ArticleEdgePullDirection {
    None,
    Top,
    Bottom,
}

internal enum class ArticleEdgePullAction {
    RequestFullText,
    OpenWebPage,
}

@Stable
internal class ArticleEdgePullState(
    private val thresholdPx: Float,
) {
    var direction by mutableStateOf(ArticleEdgePullDirection.None)
        private set

    var distancePulled by mutableFloatStateOf(0f)
        private set

    var isRequestingFullText by mutableStateOf(false)
        private set

    val progress: Float
        get() = distancePulled * DRAG_MULTIPLIER / thresholdPx

    val isArmed: Boolean
        get() = progress >= 1f

    val hasPull: Boolean
        get() = direction != ArticleEdgePullDirection.None && distancePulled > 0f

    fun updateRequestingFullText(requesting: Boolean) {
        isRequestingFullText = requesting
        if (requesting) {
            resetPull()
        }
    }

    fun onPull(
        delta: Float,
        canPullFromTop: Boolean,
        canPullFromBottom: Boolean,
    ): Float {
        if (isRequestingFullText || delta == 0f) return 0f

        val oldDistance = distancePulled
        when (direction) {
            ArticleEdgePullDirection.None ->
                when {
                    delta > 0f && canPullFromTop -> {
                        direction = ArticleEdgePullDirection.Top
                        distancePulled = delta
                    }

                    delta < 0f && canPullFromBottom -> {
                        direction = ArticleEdgePullDirection.Bottom
                        distancePulled = -delta
                    }
                }

            ArticleEdgePullDirection.Top -> distancePulled = (distancePulled + delta).coerceAtLeast(0f)
            ArticleEdgePullDirection.Bottom -> distancePulled = (distancePulled - delta).coerceAtLeast(0f)
        }

        if (distancePulled == 0f) {
            direction = ArticleEdgePullDirection.None
        }
        if (distancePulled == oldDistance && direction == ArticleEdgePullDirection.None) return 0f
        val consumedDistance = distancePulled - oldDistance
        return when (direction) {
            ArticleEdgePullDirection.Top -> consumedDistance
            ArticleEdgePullDirection.Bottom -> -consumedDistance
            ArticleEdgePullDirection.None -> delta.coerceIn(-oldDistance, oldDistance)
        }
    }

    fun onRelease(
        topEnabled: Boolean,
        bottomEnabled: Boolean,
    ): ArticleEdgePullAction? {
        val action =
            when {
                !isArmed -> null
                direction == ArticleEdgePullDirection.Top && topEnabled && !isRequestingFullText ->
                    ArticleEdgePullAction.RequestFullText

                direction == ArticleEdgePullDirection.Bottom && bottomEnabled -> ArticleEdgePullAction.OpenWebPage
                else -> null
            }
        resetPull()
        return action
    }

    private fun resetPull() {
        direction = ArticleEdgePullDirection.None
        distancePulled = 0f
    }

    private companion object {
        const val DRAG_MULTIPLIER = 0.5f
    }
}

@Composable
internal fun rememberArticleEdgePullState(
    isRequestingFullText: Boolean,
    threshold: androidx.compose.ui.unit.Dp = 80.dp,
): ArticleEdgePullState {
    val thresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { threshold.toPx() }
    val state = remember(thresholdPx) { ArticleEdgePullState(thresholdPx) }
    SideEffect {
        state.updateRequestingFullText(isRequestingFullText)
    }
    return state
}

@Composable
internal fun Modifier.articleEdgePull(
    state: ArticleEdgePullState,
    canPullFromTop: () -> Boolean,
    canPullFromBottom: () -> Boolean,
    topEnabled: Boolean,
    bottomEnabled: Boolean,
    onRequestFullText: () -> Unit,
    onOpenWebPage: () -> Unit,
): Modifier {
    val currentCanPullFromTop by rememberUpdatedState(canPullFromTop)
    val currentCanPullFromBottom by rememberUpdatedState(canPullFromBottom)
    val currentTopEnabled by rememberUpdatedState(topEnabled)
    val currentBottomEnabled by rememberUpdatedState(bottomEnabled)
    val currentOnRequestFullText by rememberUpdatedState(onRequestFullText)
    val currentOnOpenWebPage by rememberUpdatedState(onOpenWebPage)
    val connection =
        remember(state) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source != NestedScrollSource.UserInput || !state.hasPull) return Offset.Zero
                    val reversesCurrentPull =
                        (state.direction == ArticleEdgePullDirection.Top && available.y < 0f) ||
                            (state.direction == ArticleEdgePullDirection.Bottom && available.y > 0f)
                    if (!reversesCurrentPull) return Offset.Zero
                    return Offset(
                        x = 0f,
                        y =
                            state.onPull(
                                delta = available.y,
                                canPullFromTop = currentCanPullFromTop(),
                                canPullFromBottom = currentCanPullFromBottom(),
                            ),
                    )
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source != NestedScrollSource.UserInput) return Offset.Zero
                    val canStartTopPull = available.y > 0f && currentTopEnabled && currentCanPullFromTop()
                    val canStartBottomPull = available.y < 0f && currentBottomEnabled && currentCanPullFromBottom()
                    if (!state.hasPull && !canStartTopPull && !canStartBottomPull) return Offset.Zero
                    return Offset(
                        x = 0f,
                        y =
                            state.onPull(
                                delta = available.y,
                                canPullFromTop = canStartTopPull,
                                canPullFromBottom = canStartBottomPull,
                            ),
                    )
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (!state.hasPull) return Velocity.Zero
                    when (state.onRelease(currentTopEnabled, currentBottomEnabled)) {
                        ArticleEdgePullAction.RequestFullText -> currentOnRequestFullText()
                        ArticleEdgePullAction.OpenWebPage -> currentOnOpenWebPage()
                        null -> Unit
                    }
                    return Velocity(0f, available.y)
                }
            }
        }
    return nestedScroll(connection)
}

@Composable
internal fun ArticleEdgePullIndicator(
    state: ArticleEdgePullState,
    direction: ArticleEdgePullDirection,
    modifier: Modifier = Modifier,
) {
    val visible = state.direction == direction || (direction == ArticleEdgePullDirection.Top && state.isRequestingFullText)
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                if (direction == ArticleEdgePullDirection.Top && state.isRequestingFullText) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector =
                            if (direction == ArticleEdgePullDirection.Top) {
                                Icons.Default.ArrowDownward
                            } else {
                                Icons.Default.ArrowUpward
                            },
                        contentDescription = null,
                    )
                }
                Text(
                    text =
                        when {
                            direction == ArticleEdgePullDirection.Top && state.isRequestingFullText ->
                                stringResource(R.string.edge_pull_fetching_full_article)

                            direction == ArticleEdgePullDirection.Top && state.isArmed ->
                                stringResource(R.string.edge_pull_release_to_fetch_full_article)

                            direction == ArticleEdgePullDirection.Top ->
                                stringResource(R.string.edge_pull_pull_to_fetch_full_article)

                            state.isArmed -> stringResource(R.string.edge_pull_release_to_open_web_page)
                            else -> stringResource(R.string.edge_pull_pull_to_open_web_page)
                        },
                )
            }
        }
    }
}
