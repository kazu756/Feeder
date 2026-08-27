package com.nononsenseapps.feeder.ui.compose.feed

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeedItemMagazineTest {
    @Test
    fun wideWidthUsesLargeThumbnail() {
        assertEquals(
            MagazineThumbnailSize.LARGE,
            magazineThumbnailSize(
                availableWidth = 292.dp,
                showThumbnail = true,
                hasImage = true,
                showBookmark = false,
            ),
        )
    }

    @Test
    fun mediumWidthUsesMediumThumbnail() {
        assertEquals(
            MagazineThumbnailSize.MEDIUM,
            magazineThumbnailSize(
                availableWidth = 260.dp,
                showThumbnail = true,
                hasImage = true,
                showBookmark = false,
            ),
        )
    }

    @Test
    fun narrowWidthHidesThumbnail() {
        assertNull(
            magazineThumbnailSize(
                availableWidth = 259.dp,
                showThumbnail = true,
                hasImage = true,
                showBookmark = false,
            ),
        )
    }

    @Test
    fun bookmarkWidthIsReserved() {
        assertEquals(
            MagazineThumbnailSize.MEDIUM,
            magazineThumbnailSize(
                availableWidth = 319.dp,
                showThumbnail = true,
                hasImage = true,
                showBookmark = true,
            ),
        )
        assertNull(
            magazineThumbnailSize(
                availableWidth = 287.dp,
                showThumbnail = true,
                hasImage = true,
                showBookmark = true,
            ),
        )
    }

    @Test
    fun disabledThumbnailsAlwaysHideImage() {
        assertNull(
            magazineThumbnailSize(
                availableWidth = 600.dp,
                showThumbnail = false,
                hasImage = true,
                showBookmark = false,
            ),
        )
    }

    @Test
    fun missingImageAlwaysHidesThumbnail() {
        assertNull(
            magazineThumbnailSize(
                availableWidth = 600.dp,
                showThumbnail = true,
                hasImage = false,
                showBookmark = false,
            ),
        )
    }
}
