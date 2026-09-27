package com.lukdut.swipeclean.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class PhotoEmbeddingExtractorTest {
    @Test fun downloadedModelProducesStableNormalizedEmbeddingsWithCorrectTransparency() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = TestModelFixture.install(context)
        val bitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
        try {
            PhotoEmbeddingExtractor(model).use { extractor ->
                bitmap.eraseColor(Color.WHITE)
                val white = extractor.extract(bitmap)
                assertEquals(model.spec.dimensions, white.values.size)
                assertEquals(1.0, sqrt(white.values.sumOf { it.toDouble() * it }), 0.0001)
                bitmap.eraseColor(Color.TRANSPARENT)
                assertEquals(1f, white.similarity(extractor.extract(bitmap)), 0.0001f)
                bitmap.eraseColor(Color.BLACK)
                val black = extractor.extract(bitmap)
                assertTrue(white.similarity(black) < 0.99f)
                assertEquals(1f, black.similarity(extractor.extract(bitmap)), 0.0001f)
            }
        } finally { bitmap.recycle() }
    }
}
