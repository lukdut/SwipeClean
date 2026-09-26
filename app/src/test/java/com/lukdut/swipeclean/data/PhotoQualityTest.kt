package com.lukdut.swipeclean.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class PhotoQualityTest {
    private val size = 256
    private fun gray(value: Int): Int = (255 shl 24) or (value shl 16) or (value shl 8) or value
    private fun analyze(pixel: (Int, Int) -> Int): PhotoQuality = PhotoQualityCalculator.calculate(
        IntArray(size * size) { pixel(it % size, it / size) }, size, size
    )

    @Test
    fun blackAndWhiteFramesHaveDifferentExposureReasons() {
        val black = analyze { _, _ -> gray(0) }
        val white = analyze { _, _ -> gray(255) }
        assertTrue(black.dark > 0.99f)
        assertEquals(0f, black.bright, 0f)
        assertTrue(white.bright > 0.99f)
        assertEquals(0f, white.dark, 0f)
        assertEquals("Очень тёмный кадр", black.reason(PhotoSettings()))
    }

    @Test
    fun flatFrameIsLowDetailRatherThanBlur() {
        val quality = analyze { _, _ -> gray(120) }
        assertTrue(quality.lowDetail > 0.99f)
        assertEquals(0f, quality.blur, 0f)
        assertEquals(0f, quality.dark, 0f)
        assertEquals(0f, quality.bright, 0f)
    }

    @Test
    fun smoothTransitionsScoreHigherForBlurThanSharpEdges() {
        val sharp = analyze { x, _ -> gray(if (x < 128) 40 else 210) }
        val smooth = analyze { x, _ ->
            gray((40 + 170 / (1 + kotlin.math.exp(-(x - 128) / 16.0))).roundToInt())
        }
        assertEquals(0f, sharp.blur, 0f)
        assertTrue(smooth.blur > 0.8f)
    }

    @Test
    fun sharpSubjectProtectsAnOtherwiseSmoothBackground() {
        val quality = analyze { x, y ->
            if (x in 95..160 && y in 95..160) gray(if ((x / 4 + y / 4) % 2 == 0) 30 else 220)
            else gray(100 + x / 4)
        }
        assertEquals(0f, quality.blur, 0f)
    }

    @Test
    fun ordinaryContrastAndSmallClippedHighlightsDoNotGetPriority() {
        val quality = analyze { x, y ->
            gray(if (x < 12) 255 else if ((x / 8 + y / 8) % 2 == 0) 50 else 200)
        }
        assertEquals(0f, quality.score(PhotoSettings()), 0f)
    }

    @Test
    fun transparentPixelsDoNotLookLikeBlackPhotos() {
        val quality = analyze { _, _ -> 0 }
        assertEquals(0f, quality.dark, 0f)
    }

    @Test
    fun tinyImagesAreNotLabeledBlurry() {
        val quality = PhotoQualityCalculator.calculate(
            IntArray(16 * 16) { gray((it % 16) * 15) }, 16, 16
        )
        assertEquals(0f, quality.blur, 0f)
    }

    @Test
    fun disabledSignalsDoNotAffectScoreOrReason() {
        val quality = PhotoQuality(0.8f, 1f, 0f, 0.1f)
        val settings = PhotoSettings(blur = Priority.OFF, dark = Priority.OFF, lowDetail = Priority.OFF)
        assertEquals(0f, quality.score(settings), 0f)
        assertNull(quality.reason(settings))
    }

    @Test
    fun higherPriorityChangesTheDominantReasonWithoutReanalysis() {
        val quality = PhotoQuality(0.7f, 0.9f, 0f, 0f)
        assertEquals("Очень тёмный кадр", quality.reason(PhotoSettings()))
        assertEquals("Возможно размытие", quality.reason(PhotoSettings(blur = Priority.HIGH)))
        assertEquals(1.4f, quality.score(PhotoSettings(blur = Priority.HIGH)), 0.0001f)
    }

    @Test
    fun correlatedSignalsDoNotDoubleCountTheSameDefect() {
        assertEquals(1f, PhotoQuality(0f, 1f, 0f, 1f).score(PhotoSettings()), 0f)
    }
}
