package com.nononsenseapps.feeder.ui.compose.feed

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.db.room.ID_UNSET
import com.nononsenseapps.feeder.model.MediaImage
import com.nononsenseapps.feeder.ui.compose.theme.PreviewTheme
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class FeedItemMagazineTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun wideWidthUsesLargeThumbnailAndKeepsFullTitle() {
        setMagazine(width = 292, bookmarked = false)

        composeTestRule
            .onNodeWithTag(MAGAZINE_THUMBNAIL_TEST_TAG)
            .assertWidthIsEqualTo(96.dp)
            .assertHeightIsEqualTo(72.dp)
        composeTestRule.onNodeWithText(TARGET_TITLE).assertIsDisplayed()
    }

    @Test
    fun mediumWidthUsesMediumThumbnail() {
        setMagazine(width = 260, bookmarked = false)

        composeTestRule
            .onNodeWithTag(MAGAZINE_THUMBNAIL_TEST_TAG)
            .assertWidthIsEqualTo(64.dp)
            .assertHeightIsEqualTo(48.dp)
    }

    @Test
    fun narrowWidthHidesThumbnailAndKeepsFullTitle() {
        setMagazine(width = 259, bookmarked = false)

        composeTestRule.onAllNodesWithTag(MAGAZINE_THUMBNAIL_TEST_TAG).assertCountEquals(0)
        composeTestRule.onNodeWithText(TARGET_TITLE).assertIsDisplayed()
    }

    @Test
    fun bookmarkWidthCanReduceThumbnailSize() {
        setMagazine(width = 319, bookmarked = true)

        composeTestRule
            .onNodeWithTag(MAGAZINE_THUMBNAIL_TEST_TAG)
            .assertWidthIsEqualTo(64.dp)
            .assertHeightIsEqualTo(48.dp)
    }

    @Test
    fun disabledThumbnailsHideImage() {
        setMagazine(width = 600, bookmarked = false, showThumbnail = false)

        composeTestRule.onAllNodesWithTag(MAGAZINE_THUMBNAIL_TEST_TAG).assertCountEquals(0)
        composeTestRule.onNodeWithText(TARGET_TITLE).assertIsDisplayed()
    }

    private fun setMagazine(
        width: Int,
        bookmarked: Boolean,
        showThumbnail: Boolean = true,
    ) {
        composeTestRule.setContent {
            PreviewTheme {
                FeedItemMagazine(
                    state =
                        FeedItemState(
                            item = targetArticle(bookmarked),
                            showThumbnail = showThumbnail,
                            bookmarkIndicator = true,
                        ),
                    modifier = Modifier.width(width.dp),
                )
            }
        }
    }

    private fun targetArticle(bookmarked: Boolean) =
        FeedListItem(
            id = ID_UNSET,
            title = TARGET_TITLE,
            snippet = "",
            feedTitle = "時事通信",
            unread = true,
            pubDate = "Aug 27, 2026",
            image = MediaImage(url = "https://example.com/image.jpg"),
            link = null,
            bookmarked = bookmarked,
            feedImageUrl = null,
            primarySortTime = Instant.EPOCH,
            rawPubDate = null,
            wordCount = 0,
        )

    companion object {
        private const val TARGET_TITLE = "九州旅行「応援割」実施へ　復興基金を検討―熊本地震支援策"
    }
}
