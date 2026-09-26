package com.lukdut.swipeclean.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lukdut.swipeclean.data.MediaPhoto
import com.lukdut.swipeclean.ui.theme.SwipeCleanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SwipeContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val photos = (1L..5L).map { id ->
        MediaPhoto(id, Uri.EMPTY, "Photo $id", 0L, 0L)
    }
    private val reviewed = mutableListOf<Pair<Long, Boolean>>()

    @Test
    fun consecutiveSwipesAdvanceBeforeExitAnimationsFinish() {
        showPhotos()

        swipePhoto(id = 1, keep = true)
        compose.runOnIdle { assertEquals(listOf(1L to true), reviewed) }

        swipePhoto(id = 2, keep = false)
        compose.runOnIdle {
            assertEquals(listOf(1L to true, 2L to false), reviewed)
        }
        compose.onNodeWithText("3 / 5").assertExists()

        // Finishing either old animation must not review another photo.
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle {
            assertEquals(listOf(1L to true, 2L to false), reviewed)
        }
    }

    @Test
    fun consecutiveButtonActionsAdvanceBeforeExitAnimationsFinish() {
        showPhotos()

        compose.onNodeWithContentDescription("Оставить").performClick()
        nextFrame()
        compose.onNodeWithContentDescription("Удалить").performClick()
        nextFrame()

        compose.runOnIdle {
            assertEquals(listOf(1L to true, 2L to false), reviewed)
        }
        compose.onNodeWithText("3 / 5").assertExists()
    }

    @Test
    fun newSwipeInterruptsReturnAnimation() {
        showPhotos()

        compose.onNodeWithContentDescription("Photo 1").performTouchInput {
            swipe(center, center + Offset(width * 0.2f, height * 0.1f), durationMillis = 48)
        }
        nextFrame()
        compose.runOnIdle { assertEquals(emptyList<Pair<Long, Boolean>>(), reviewed) }

        swipePhoto(id = 1, keep = false)
        compose.runOnIdle { assertEquals(listOf(1L to false), reviewed) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(listOf(1L to false), reviewed) }
    }

    @Test
    fun samePhotoCanOnlyBeReviewedOnce() {
        showPhotos(advancePhoto = false)

        compose.onNodeWithContentDescription("Оставить").performClick()
        compose.onNodeWithContentDescription("Удалить").performClick()
        compose.mainClock.advanceTimeBy(1_000)

        compose.runOnIdle { assertEquals(listOf(1L to true), reviewed) }
    }

    private fun showPhotos(advancePhoto: Boolean = true) {
        compose.setContent {
            var index by remember { mutableIntStateOf(0) }
            fun review(keep: Boolean) {
                reviewed += photos[index].id to keep
                if (advancePhoto) index++
            }

            SwipeCleanTheme {
                SwipeContent(
                    currentPhoto = photos[index],
                    nextPhoto = photos.getOrNull(index + 1),
                    progress = index to photos.size,
                    onDelete = { review(false) },
                    onKeep = { review(true) },
                    onPhotoTap = {}
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun swipePhoto(id: Long, keep: Boolean) {
        compose.onNodeWithContentDescription("Photo $id").performTouchInput {
            val startX = if (keep) width * 0.1f else width * 0.9f
            val endX = if (keep) width * 0.9f else width * 0.1f
            swipe(Offset(startX, centerY), Offset(endX, centerY), durationMillis = 48)
        }
        nextFrame()
    }

    private fun nextFrame() {
        // Recompose and start animations, without waiting for their 280 ms duration.
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }
}
