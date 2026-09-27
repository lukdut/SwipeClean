package com.lukdut.swipeclean.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
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
    fun pinchZoomsAndPansWithoutReviewing() {
        showPhotos()

        pinchPhoto(startRadius = 0.1f, endRadius = 0.25f)
        assertScale(id = 1, percent = 250)
        swipePhoto(id = 1, keep = true)
        swipePhoto(id = 1, keep = false)

        compose.runOnIdle {
            assertEquals(emptyList<Pair<Long, Boolean>>(), reviewed)
        }
        assertScale(id = 1, percent = 250)
    }

    @Test
    fun zoomOutRestoresSwipeOnlyAfterAllFingersLift() {
        showPhotos()
        pinchPhoto(startRadius = 0.1f, endRadius = 0.25f)

        compose.onNodeWithContentDescription("Photo 1").performTouchInput {
            down(0, Offset(width * 0.1f, centerY))
            down(1, Offset(width * 0.9f, centerY))
            updatePointerTo(0, Offset(width * 0.49f, centerY))
            updatePointerTo(1, Offset(width * 0.51f, centerY))
            move()
            up(1)
            moveTo(0, Offset(width * 0.95f, centerY))
            up(0)
        }
        nextFrame()
        assertScale(id = 1, percent = 100)
        compose.runOnIdle { assertEquals(emptyList<Pair<Long, Boolean>>(), reviewed) }

        swipePhoto(id = 1, keep = true)
        compose.runOnIdle { assertEquals(listOf(1L to true), reviewed) }
    }

    @Test
    fun addingSecondFingerCancelsPendingReview() {
        showPhotos()

        compose.onNodeWithContentDescription("Photo 1").performTouchInput {
            down(0, Offset(width * 0.1f, centerY))
            moveTo(0, Offset(width * 0.7f, centerY))
            down(1, Offset(width * 0.9f, centerY))
            updatePointerTo(0, Offset(width * 0.6f, centerY))
            updatePointerTo(1, Offset(width * 0.95f, centerY))
            move()
            up(1)
            moveTo(0, Offset(width * 0.95f, centerY))
            up(0)
        }
        compose.mainClock.advanceTimeBy(1_000)

        assertScale(id = 1, percent = 175)
        compose.runOnIdle {
            assertEquals(emptyList<Pair<Long, Boolean>>(), reviewed)
        }
    }

    @Test
    fun twoFingerPanAtOriginalSizeDoesNotReviewPhoto() {
        showPhotos()

        compose.onNodeWithContentDescription("Photo 1").performTouchInput {
            pinch(
                start0 = Offset(width * 0.1f, centerY),
                end0 = Offset(width * 0.6f, centerY),
                start1 = Offset(width * 0.3f, centerY),
                end1 = Offset(width * 0.8f, centerY),
                durationMillis = 96
            )
        }
        nextFrame()
        assertScale(id = 1, percent = 100)
        compose.runOnIdle {
            assertEquals(emptyList<Pair<Long, Boolean>>(), reviewed)
        }
        swipePhoto(id = 1, keep = false)
        compose.runOnIdle { assertEquals(listOf(1L to false), reviewed) }
    }

    @Test
    fun zoomIsLimitedAndResetsForNextPhotoAndUndo() {
        showPhotos()
        pinchPhoto(startRadius = 0.02f, endRadius = 0.4f)
        assertScale(id = 1, percent = 500)

        compose.onNodeWithContentDescription("Оставить").performClick()
        nextFrame()
        assertScale(id = 2, percent = 100)
        compose.onNodeWithContentDescription("Отменить последнее действие").performClick()
        nextFrame()
        assertScale(id = 1, percent = 100)

        swipePhoto(id = 1, keep = false)
        compose.runOnIdle { assertEquals(listOf(1L to false), reviewed) }
    }

    @Test
    fun onlySingleFingerTapResetsZoomAndRestoresSwipe() {
        showPhotos()
        pinchPhoto(startRadius = 0.1f, endRadius = 0.25f)
        swipePhoto(id = 1, keep = true)
        assertScale(id = 1, percent = 250)

        compose.onNodeWithContentDescription("Photo 1").performTouchInput {
            down(0, Offset(width * 0.4f, centerY))
            down(1, Offset(width * 0.6f, centerY))
            up(1)
            up(0)
        }
        nextFrame()
        assertScale(id = 1, percent = 250)
        compose.onNodeWithContentDescription("Photo 1").performTouchInput { click() }
        nextFrame()
        assertScale(id = 1, percent = 100)
        compose.runOnIdle { assertEquals(emptyList<Pair<Long, Boolean>>(), reviewed) }

        swipePhoto(id = 1, keep = true)
        compose.runOnIdle { assertEquals(listOf(1L to true), reviewed) }
    }

    @Test
    fun resetTapToleratesSmallFingerMovement() {
        showPhotos()
        pinchPhoto(startRadius = 0.1f, endRadius = 0.25f)

        compose.onNodeWithContentDescription("Photo 1").performTouchInput {
            down(center)
            moveBy(Offset(1f, 1f))
            up()
        }
        nextFrame()
        assertScale(id = 1, percent = 100)

        swipePhoto(id = 1, keep = false)
        compose.runOnIdle { assertEquals(listOf(1L to false), reviewed) }
    }

    @Test
    fun tapAtOriginalScaleKeepsPhotoAndAllowsSwipe() {
        showPhotos()

        compose.onNodeWithContentDescription("Photo 1").performTouchInput { click() }
        nextFrame()
        assertScale(id = 1, percent = 100)
        compose.runOnIdle { assertEquals(emptyList<Pair<Long, Boolean>>(), reviewed) }

        swipePhoto(id = 1, keep = false)
        compose.runOnIdle { assertEquals(listOf(1L to false), reviewed) }
    }

    @Test
    fun cancelledSwipeDoesNotReviewPhoto() {
        showPhotos()

        compose.onNodeWithContentDescription("Photo 1").performTouchInput {
            down(Offset(width * 0.1f, centerY))
            moveTo(Offset(width * 0.9f, centerY))
            cancel()
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(emptyList<Pair<Long, Boolean>>(), reviewed) }

        swipePhoto(id = 1, keep = true)
        compose.runOnIdle { assertEquals(listOf(1L to true), reviewed) }
    }

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

    @Test
    fun undoReturnsDismissedPhotoBeforeItsAnimationFinishesAndAllowsChangingDecision() {
        showPhotos()
        compose.onNodeWithContentDescription("Отменить последнее действие").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Оставить").performClick()
        nextFrame()
        compose.onNodeWithContentDescription("Отменить последнее действие").performClick()
        nextFrame()
        compose.onNodeWithText("1 / 5").assertExists()
        compose.onNodeWithContentDescription("Отменить последнее действие").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Photo 1").assertExists()
        compose.onNodeWithContentDescription("Удалить").performClick()
        nextFrame()
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(listOf(1L to false), reviewed) }
        compose.onNodeWithText("2 / 5").assertExists()
    }

    private fun showPhotos(advancePhoto: Boolean = true) {
        compose.setContent {
            var index by remember { mutableIntStateOf(0) }
            var canUndo by remember { mutableStateOf(false) }
            fun review(keep: Boolean) {
                reviewed += photos[index].id to keep
                canUndo = true
                if (advancePhoto) index++
            }

            SwipeCleanTheme {
                SwipeContent(
                    currentPhoto = photos[index],
                    nextPhoto = photos.getOrNull(index + 1),
                    progress = index to photos.size,
                    onDelete = { review(false) },
                    onKeep = { review(true) },
                    canUndo = canUndo,
                    onUndo = {
                        reviewed.removeAt(reviewed.lastIndex)
                        index--
                        canUndo = reviewed.isNotEmpty()
                    }
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

    private fun pinchPhoto(startRadius: Float, endRadius: Float) {
        compose.onNodeWithContentDescription("Photo 1").performTouchInput {
            pinch(
                start0 = center - Offset(width * startRadius, 0f),
                end0 = center - Offset(width * endRadius, 0f),
                start1 = center + Offset(width * startRadius, 0f),
                end1 = center + Offset(width * endRadius, 0f),
                durationMillis = 96
            )
        }
        nextFrame()
    }

    private fun assertScale(id: Long, percent: Int) {
        compose.onNodeWithContentDescription("Photo $id").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Масштаб: $percent%")
        )
    }

    private fun nextFrame() {
        // Recompose and start animations, without waiting for their 280 ms duration.
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }
}
