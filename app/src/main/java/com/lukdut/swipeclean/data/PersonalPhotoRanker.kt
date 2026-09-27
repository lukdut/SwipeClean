package com.lukdut.swipeclean.data

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

enum class FeedbackDecision(val weight: Float, val direction: Float) {
    KEPT(1f, -1f), TRASH(0.5f, 1f), DELETED(1f, 1f)
}

data class FeedbackExample(
    val photoKey: String,
    val embedding: PhotoEmbedding,
    val decision: FeedbackDecision,
    val ageDays: Float?,
    val decidedAt: Long
)

/** Conservative preference evidence, NOT a probability that an image is disposable. */
class PersonalPhotoRanker(examples: List<FeedbackExample>) {
    private data class Indexed(val example: FeedbackExample, val sketch: FloatArray)
    private data class Neighbor(val example: FeedbackExample, val similarity: Float)

    // Balanced, bounded history keeps scoring independent of the lifetime number of swipes.
    private val examples = examples.distinctBy { it.photoKey }
        .groupBy { it.decision == FeedbackDecision.KEPT }.values.flatMap { group ->
            group.sortedByDescending { it.decidedAt }.take(128)
        }.map { Indexed(it, sketch(it.embedding.values)) }

    fun score(key: String, embedding: PhotoEmbedding, ageDays: Float?): Float {
        if (examples.size < 3) return 0f
        val sketch = sketch(embedding.values)
        val shortlist = PriorityQueue<Neighbor>(compareBy { it.similarity })
        for (indexed in examples) {
            if (indexed.example.photoKey == key) continue
            val similarity = dot(sketch, indexed.sketch)
            if (shortlist.size < 48 || similarity > checkNotNull(shortlist.peek()).similarity) {
                shortlist.add(Neighbor(indexed.example, similarity))
                if (shortlist.size > 48) shortlist.poll()
            }
        }
        // The sketch only selects candidates; evidence always uses the full model vector.
        val neighbors = shortlist.map {
            Neighbor(it.example, embedding.similarity(it.example.embedding))
        }.filter { it.similarity >= MIN_SIMILARITY }
            .sortedByDescending { it.similarity }.take(8)
        if (neighbors.size < 3) return 0f
        var evidence = 0f
        var mass = 0f
        for ((example, similarity) in neighbors) {
            val closeness = (similarity - MIN_SIMILARITY) / (1f - MIN_SIMILARITY)
            val ageWeight = if (ageDays != null && example.ageDays != null) {
                exp(-abs(ln(1f + ageDays.coerceAtLeast(0f)) -
                    ln(1f + example.ageDays.coerceAtLeast(0f))) / 2f).coerceAtLeast(0.2f)
            } else 1f
            val weight = closeness * closeness * ageWeight * example.decision.weight
            mass += weight
            evidence += weight * example.decision.direction
        }
        return evidence / (3f + mass)
    }

    companion object {
        // Initial conservative heuristic; tune on held-out decisions, not on the training history.
        const val MIN_SIMILARITY = 0.70f

        private fun sketch(values: FloatArray): FloatArray {
            val result = FloatArray(64)
            for (i in values.indices) {
                val hash = (i * -1640531527) xor (i ushr 3)
                result[(hash ushr 1) and 63] += values[i] * if (hash and 1 == 0) 1f else -1f
            }
            val norm = sqrt(result.sumOf { it.toDouble() * it }).toFloat()
            if (norm > 0f) for (i in result.indices) result[i] /= norm
            return result
        }

        private fun dot(a: FloatArray, b: FloatArray): Float {
            var sum = 0f
            for (i in a.indices) sum += a[i] * b[i]
            return sum
        }
    }
}

data class UnwantedPhotoScore(val value: Float, val reason: String?)

object UnwantedPhotoScorer {
    fun score(quality: PhotoQuality?, personal: Float, settings: PhotoSettings): UnwantedPhotoScore {
        val technical = quality?.score(settings) ?: 0f
        val preference = personal * settings.personalization.weight
        val reason = when {
            preference > 0.15f && preference >= technical -> "Похожие фото вы обычно удаляете"
            preference < -0.15f && -preference >= technical -> "Похожие фото вы обычно оставляете"
            else -> quality?.reason(settings)
        }
        return UnwantedPhotoScore(technical + preference, reason)
    }
}
