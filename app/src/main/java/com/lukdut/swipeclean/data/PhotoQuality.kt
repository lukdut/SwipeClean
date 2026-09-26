package com.lukdut.swipeclean.data

import kotlin.math.max
import kotlin.math.sqrt

/** Heuristic severities in 0..1, not probabilities that a photo should be deleted. */
data class PhotoQuality(
    val blur: Float,
    val dark: Float,
    val bright: Float,
    val lowDetail: Float
) {
    fun severity(signal: QualitySignal): Float = when (signal) {
        QualitySignal.BLUR -> blur
        QualitySignal.DARK -> dark
        QualitySignal.BRIGHT -> bright
        QualitySignal.LOW_DETAIL -> lowDetail
    }

    fun score(settings: PhotoSettings): Float = QualitySignal.entries.maxOf {
        severity(it) * settings.priority(it).weight
    }

    fun reason(settings: PhotoSettings): String? = QualitySignal.entries
        .maxBy { severity(it) * settings.priority(it).weight }
        .takeIf { severity(it) * settings.priority(it).weight > 0f }
        ?.reason
}

object PhotoQualityCalculator {
    // Change this whenever decoding, thresholds or feature calculations change.
    const val VERSION = 1

    /** Input is a consistently sized sRGB thumbnail, at most 256 pixels on the long edge. */
    fun calculate(pixels: IntArray, width: Int, height: Int): PhotoQuality {
        require(width >= 3 && height >= 3 && pixels.size == width * height)
        val luminance = FloatArray(pixels.size)
        var sum = 0.0
        var squares = 0.0
        var darkCount = 0
        var brightCount = 0
        pixels.forEachIndexed { index, argb ->
            val alpha = (argb ushr 24) / 255f
            // Composite transparent pixels on white, as in a document/photo preview.
            val value = (0.2126f * (argb shr 16 and 255) +
                0.7152f * (argb shr 8 and 255) + 0.0722f * (argb and 255)) * alpha +
                255f * (1f - alpha)
            luminance[index] = value
            sum += value
            squares += value * value
            if (value < 24f) darkCount++
            if (value > 245f) brightCount++
        }
        val mean = sum / pixels.size
        val deviation = sqrt(max(0.0, squares / pixels.size - mean * mean)).toFloat()

        var laplacianSum = 0.0
        var laplacianSquares = 0.0
        var strongEdges = 0
        val interiorSize = (width - 2) * (height - 2)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val laplacian = 4 * luminance[i] - luminance[i - 1] -
                    luminance[i + 1] - luminance[i - width] - luminance[i + width]
                laplacianSum += laplacian
                laplacianSquares += laplacian * laplacian
                val gradient = max(
                    kotlin.math.abs(luminance[i + 1] - luminance[i - 1]),
                    kotlin.math.abs(luminance[i + width] - luminance[i - width])
                )
                if (gradient > 24f) strongEdges++
            }
        }
        val laplacianMean = laplacianSum / interiorSize
        val sharpness = max(0.0, laplacianSquares / interiorSize - laplacianMean * laplacianMean)
        val edgeFraction = strongEdges.toFloat() / interiorSize
        // A sharp subject on a soft background should not be flagged just for its background.
        // Flat images get their own signal; very small images cannot be judged reliably for blur.
        val blur = if (minOf(width, height) >= 64 && deviation >= 12f && edgeFraction < 0.015f) {
            ((35f - sharpness.toFloat()) / 35f).coerceIn(0f, 1f)
        } else 0f

        return PhotoQuality(
            blur = blur,
            dark = ((darkCount.toFloat() / pixels.size - 0.80f) / (1f - 0.80f)).coerceIn(0f, 1f),
            bright = ((brightCount.toFloat() / pixels.size - 0.85f) / (1f - 0.85f)).coerceIn(0f, 1f),
            lowDetail = ((10f - deviation) / 10f).coerceIn(0f, 1f)
        )
    }
}
