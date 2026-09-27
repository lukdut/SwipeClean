package com.lukdut.swipeclean.data

import com.lukdut.swipeclean.data.model.ModelSpec
import org.junit.Assert.*
import org.junit.Test

class ModelSpecTest {
    private val model = ModelSpec("test-v1", "Test", "https://example.com/model.onnx", "a".repeat(64),
        100, "pixels", "features", 224, 768, List(3) { 0.5f }, List(3) { 0.5f })

    @Test fun changedWeightsOrPreprocessingInvalidateVectorsEvenWithTheSameReleaseName() {
        for (changed in listOf(model.copy(sha256 = "b".repeat(64)), model.copy(inputSize = 384),
            model.copy(dimensions = 512), model.copy(mean = List(3) { 0.4f }),
            model.copy(std = List(3) { 0.3f }), model.copy(outputName = "other"))) {
            assertNotEquals(model.embeddingVersion, changed.embeddingVersion)
        }
        assertEquals(model.embeddingVersion, model.copy(url = "https://mirror.example.com/model.onnx",
            displayName = "Renamed", version = "v2").embeddingVersion)
    }

    @Test fun unsupportedOrUnboundedContractsAreRejectedBeforeDownload() {
        model.validate(1)
        for (invalid in listOf(model.copy(schemaVersion = 2), model.copy(minAppVersion = 2),
            model.copy(preprocessing = "unknown"), model.copy(url = "http://example.com/model"),
            model.copy(url = "https://user:password@example.com/model"), model.copy(sha256 = "bad"),
            model.copy(sizeBytes = ModelSpec.MAX_MODEL_BYTES + 1), model.copy(inputSize = 4096),
            model.copy(dimensions = 0), model.copy(dimensions = 100_000), model.copy(mean = emptyList()),
            model.copy(std = listOf(0f, 1f, 1f)), model.copy(mean = List(3) { Float.NaN }))) {
            assertThrows(IllegalArgumentException::class.java) { invalid.validate(1) }
        }
    }

    @Test fun vectorsSupportDifferentModelDimensionsWithoutAcceptingMismatchedCache() {
        for (dimensions in listOf(512, 768, 1024)) {
            val vector = checkNotNull(PhotoEmbedding.from(FloatArray(dimensions) { if (it == 0) 1f else 0f }))
            val restored = checkNotNull(PhotoEmbedding.decode(vector.encode(), dimensions))
            assertEquals(1f, vector.similarity(restored), 0.0001f)
            assertNull(PhotoEmbedding.decode(vector.encode(), dimensions + 1))
        }
        val a = checkNotNull(PhotoEmbedding.from(floatArrayOf(1f, 0f)))
        val b = checkNotNull(PhotoEmbedding.from(floatArrayOf(1f, 0f, 0f)))
        assertThrows(IllegalArgumentException::class.java) { a.similarity(b) }
    }
}
