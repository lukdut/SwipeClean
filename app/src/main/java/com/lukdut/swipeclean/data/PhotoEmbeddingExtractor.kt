package com.lukdut.swipeclean.data

import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.lukdut.swipeclean.data.model.InstalledModel
import java.nio.FloatBuffer

/** Owned by one analysis pass. Inference runs off the main thread, one image at a time. */
class PhotoEmbeddingExtractor(model: InstalledModel) : AutoCloseable {
    private val spec = model.spec
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        environment.createSession(model.file.absolutePath, options)
    }

    init {
        try {
            require(session.inputNames == setOf(spec.inputName))
            val input = session.inputInfo.getValue(spec.inputName).info as TensorInfo
            val output = session.outputInfo.getValue(spec.outputName).info as TensorInfo
            require(input.type == OnnxJavaType.FLOAT && output.type == OnnxJavaType.FLOAT)
            fun matches(actual: LongArray, expected: LongArray) = actual.size == expected.size &&
                actual.indices.all { actual[it] == expected[it] || actual[it] < 0 }
            require(matches(input.shape, longArrayOf(1, 3, spec.inputSize.toLong(), spec.inputSize.toLong())))
            require(matches(output.shape, longArrayOf(1, spec.dimensions.toLong())))
        } catch (e: Exception) {
            session.close()
            throw e
        }
    }

    @Synchronized
    fun extract(bitmap: Bitmap): PhotoEmbedding {
        val side = spec.inputSize
        val resized = Bitmap.createScaledBitmap(bitmap, side, side, true)
        try {
            val pixels = IntArray(side * side)
            resized.getPixels(pixels, 0, side, 0, 0, side, side)
            val input = FloatArray(pixels.size * 3)
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val alpha = (pixel ushr 24) / 255f
                for (channel in 0..2) {
                    // White alpha compositing, RGB/NCHW; normalization is part of model identity.
                    val component = (pixel shr (16 - 8 * channel)) and 255
                    input[channel * pixels.size + i] =
                        ((component * alpha + 255f * (1f - alpha)) / 255f - spec.mean[channel]) / spec.std[channel]
                }
            }
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(input),
                longArrayOf(1, 3, side.toLong(), side.toLong())).use { tensor ->
                session.run(mapOf(spec.inputName to tensor), setOf(spec.outputName)).use { result ->
                    val output = result.get(spec.outputName).get() as OnnxTensor
                    require(output.info.shape.contentEquals(longArrayOf(1, spec.dimensions.toLong())))
                    val values = FloatArray(spec.dimensions)
                    output.floatBuffer.get(values)
                    return checkNotNull(PhotoEmbedding.from(values)) { "Invalid image embedding" }
                }
            }
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    }

    @Synchronized
    override fun close() = session.close()
}
