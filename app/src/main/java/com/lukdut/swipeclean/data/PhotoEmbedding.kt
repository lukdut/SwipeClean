package com.lukdut.swipeclean.data

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import com.lukdut.swipeclean.data.model.ModelSpec

/** Per-vector int8 quantization uses one byte per dimension. Cosine cancels the scale. */
class PhotoEmbedding private constructor(private val coordinates: ByteArray, private val norm: Float) {
    val dimensions: Int get() = coordinates.size
    val values: FloatArray get() = FloatArray(coordinates.size) { coordinates[it] / norm }

    fun encode(): ByteArray = coordinates.copyOf()

    fun similarity(other: PhotoEmbedding): Float {
        require(dimensions == other.dimensions) { "Incompatible embedding dimensions" }
        var dot = 0
        for (i in coordinates.indices) dot += coordinates[i] * other.coordinates[i]
        return (dot / (norm * other.norm)).coerceIn(-1f, 1f)
    }

    companion object {
        fun from(values: FloatArray): PhotoEmbedding? {
            if (values.size !in 2..ModelSpec.MAX_DIMENSIONS || values.any { !it.isFinite() }) return null
            val maximum = values.maxOf { abs(it) }
            if (maximum < 1e-8f) return null
            return decode(ByteArray(values.size) { (values[it] / maximum * 127f).roundToInt().toByte() }, values.size)
        }

        fun decode(bytes: ByteArray?, dimensions: Int): PhotoEmbedding? {
            if (dimensions !in 2..ModelSpec.MAX_DIMENSIONS || bytes == null || bytes.size != dimensions) return null
            val norm = sqrt(bytes.sumOf { it.toDouble() * it }).toFloat()
            if (norm == 0f) return null
            return PhotoEmbedding(bytes.copyOf(), norm)
        }
    }
}
