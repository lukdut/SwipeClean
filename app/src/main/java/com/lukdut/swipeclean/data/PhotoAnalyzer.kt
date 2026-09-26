package com.lukdut.swipeclean.data

import android.content.ContentResolver
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.roundToInt

class PhotoAnalyzer(private val resolver: ContentResolver) {
    suspend fun analyze(photo: MediaPhoto): PhotoQuality? = withContext(Dispatchers.IO) {
        ensureActive()
        try {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, photo.uri)) {
                    decoder, info, _ ->
                val scale = minOf(1f, 256f / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1)
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            }
            try {
                ensureActive()
                if (bitmap.width < 3 || bitmap.height < 3) return@withContext null
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                PhotoQualityCalculator.calculate(pixels, bitmap.width, bitmap.height)
            } finally {
                bitmap.recycle()
            }
        } catch (_: IOException) {
            null // Removed, unavailable, corrupt or unsupported media must not stop the queue.
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
