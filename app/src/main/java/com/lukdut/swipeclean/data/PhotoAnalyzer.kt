package com.lukdut.swipeclean.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import com.lukdut.swipeclean.data.model.InstalledModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.roundToInt

data class PhotoAnalysis(val quality: PhotoQuality, val embedding: PhotoEmbedding, val embeddingVersion: String)

class PhotoAnalyzer(context: Context, private val model: InstalledModel) : AutoCloseable {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val extractor = lazy { PhotoEmbeddingExtractor(model) }

    suspend fun analyze(photo: MediaPhoto): PhotoAnalysis? = withContext(Dispatchers.IO) {
        ensureActive()
        try {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, photo.uri)) {
                    decoder, info, _ ->
                val scale = minOf(1f, maxOf(256, model.spec.inputSize).toFloat() / maxOf(info.size.width, info.size.height))
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
                val quality = PhotoQualityCalculator.calculate(pixels, bitmap.width, bitmap.height)
                ensureActive()
                PhotoAnalysis(quality, extractor.value.extract(bitmap), model.spec.embeddingVersion)
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

    override fun close() {
        if (extractor.isInitialized()) extractor.value.close()
    }
}
