package com.nononsenseapps.feeder.ui.compose.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.ui.compose.minimumTouchSize
import kotlin.math.roundToInt

private val magazineHorizontalPadding = 24.dp
private val magazineThumbnailSpacing = 12.dp
private val magazineBookmarkWidth = 28.dp
private val magazineMinimumTextWidth = 160.dp
internal const val MAGAZINE_THUMBNAIL_TEST_TAG = "magazine_thumbnail"

internal enum class MagazineThumbnailSize(
    val width: Dp,
    val height: Dp,
) {
    LARGE(width = 96.dp, height = 72.dp),
    MEDIUM(width = 64.dp, height = 48.dp),
}

internal fun magazineThumbnailSize(
    availableWidth: Dp,
    showThumbnail: Boolean,
    hasImage: Boolean,
    showBookmark: Boolean,
): MagazineThumbnailSize? {
    if (!showThumbnail || !hasImage) return null

    val reservedWidth =
        magazineHorizontalPadding +
            magazineThumbnailSpacing +
            magazineMinimumTextWidth +
            if (showBookmark) magazineBookmarkWidth else 0.dp

    return when {
        availableWidth >= reservedWidth + MagazineThumbnailSize.LARGE.width -> MagazineThumbnailSize.LARGE
        availableWidth >= reservedWidth + MagazineThumbnailSize.MEDIUM.width -> MagazineThumbnailSize.MEDIUM
        else -> null
    }
}

@Composable
fun FeedItemMagazine(
    state: FeedItemState,
    modifier: Modifier = Modifier,
    onEvent: (FeedItemEvent) -> Unit = {},
) {
    Surface(
        modifier =
            modifier
                .alpha(if (!state.item.unread) 0.75f else 1f)
                .fillMaxWidth(),
    ) {
        BoxWithConstraints {
            val showBookmark = state.item.bookmarked && state.bookmarkIndicator
            val thumbnailSize =
                magazineThumbnailSize(
                    availableWidth = maxWidth,
                    showThumbnail = state.showThumbnail,
                    hasImage = state.item.image != null,
                    showBookmark = showBookmark,
                )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .requiredHeightIn(min = 88.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (thumbnailSize != null) {
                    val image = requireNotNull(state.item.image)
                    val imageSize =
                        with(LocalDensity.current) {
                            Size(
                                thumbnailSize.width.toPx().roundToInt(),
                                thumbnailSize.height.toPx().roundToInt(),
                            )
                        }
                    AsyncImage(
                        model =
                            ImageRequest
                                .Builder(LocalContext.current)
                                .data(image.url)
                                .scale(Scale.FILL)
                                .size(imageSize)
                                .precision(Precision.INEXACT)
                                .build(),
                        contentDescription = stringResource(R.string.article_image),
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .size(thumbnailSize.width, thumbnailSize.height)
                                .testTag(MAGAZINE_THUMBNAIL_TEST_TAG),
                    )
                }

                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(start = if (thumbnailSize != null) magazineThumbnailSpacing else 0.dp),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .requiredHeightIn(min = minimumTouchSize)
                                .padding(end = if (showBookmark) magazineBookmarkWidth else 0.dp),
                    ) {
                        FeedItemText(
                            item = state.item,
                            onOpenFeedItemInReader = { onEvent(FeedItemEvent.OpenInReader) },
                            onOpenFeedItemInCustomTab = { onEvent(FeedItemEvent.OpenInCustomTab) },
                            onOpenFeedItemInBrowser = { onEvent(FeedItemEvent.OpenInBrowser) },
                            onMarkAboveAsRead = { onEvent(FeedItemEvent.MarkAboveAsRead) },
                            onMarkBelowAsRead = { onEvent(FeedItemEvent.MarkBelowAsRead) },
                            onShareItem = { onEvent(FeedItemEvent.ShareItem) },
                            onToggleBookmark = { onEvent(FeedItemEvent.ToggleBookmarked) },
                            dropDownMenuExpanded = state.dropDownMenuExpanded,
                            onDismissDropdown = { onEvent(FeedItemEvent.DismissDropdown) },
                            maxLines = 3,
                            showOnlyTitle = true,
                            showReadingTime = false,
                        )
                    }

                    if (showBookmark) {
                        FeedItemSavedIndicator(
                            size = 24.dp,
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                }
            }
        }
    }
}
