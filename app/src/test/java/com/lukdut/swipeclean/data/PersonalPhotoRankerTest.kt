package com.lukdut.swipeclean.data

import org.junit.Assert.*
import org.junit.Test

class PersonalPhotoRankerTest {
    private fun vector(index: Int) = checkNotNull(PhotoEmbedding.from(
        FloatArray(768) { if (it == index) 1f else 0f }))
    private val target = vector(0)
    private fun examples(decision: FeedbackDecision, count: Int = 4, embedding: PhotoEmbedding = target) =
        (1..count).map { FeedbackExample("${decision.name}-$it", embedding, decision, 30f, it.toLong()) }

    @Test fun coldStartAndSingleOrRepeatedDecisionsDoNotCreateConfidence() {
        for (count in 0..2) assertEquals(0f, PersonalPhotoRanker(examples(FeedbackDecision.DELETED, count))
            .score("new", target, 30f), 0f)
        val repeated = List(20) { examples(FeedbackDecision.DELETED, 1).single() }
        assertEquals(0f, PersonalPhotoRanker(repeated).score("new", target, 30f), 0f)
    }

    @Test fun similarDeletedAndKeptPhotosMovePriorityInOppositeDirections() {
        assertTrue(PersonalPhotoRanker(examples(FeedbackDecision.DELETED)).score("new", target, 30f) > 0.4f)
        assertTrue(PersonalPhotoRanker(examples(FeedbackDecision.KEPT)).score("new", target, 30f) < -0.4f)
        assertEquals(0f, PersonalPhotoRanker(examples(FeedbackDecision.DELETED) + examples(FeedbackDecision.KEPT))
            .score("new", target, 30f), 0.00001f)
    }

    @Test fun unrelatedImagesAndTheTargetItselfAreNotEvidence() {
        assertEquals(0f, PersonalPhotoRanker(examples(FeedbackDecision.DELETED, embedding = vector(1)))
            .score("new", target, 30f), 0f)
        assertEquals(0f, PersonalPhotoRanker(examples(FeedbackDecision.DELETED, 3))
            .score("DELETED-1", target, 30f), 0f)
    }

    @Test fun confirmedDeletionIsStrongerThanABasketSwipe() {
        val marked = PersonalPhotoRanker(examples(FeedbackDecision.TRASH)).score("new", target, 30f)
        val deleted = PersonalPhotoRanker(examples(FeedbackDecision.DELETED)).score("new", target, 30f)
        assertTrue(marked > 0f)
        assertTrue(deleted > marked)
    }

    @Test fun oldPhotosDoNotStronglyTeachDeletionOfFreshPhotos() {
        val history = examples(FeedbackDecision.DELETED).map { it.copy(ageDays = 800f) }
        val ranker = PersonalPhotoRanker(history)
        assertTrue(ranker.score("new", target, 800f) > ranker.score("new", target, 1f))
        assertTrue(ranker.score("new", target, null).isFinite())
    }

    @Test fun disablingPersonalizationRestoresTechnicalRankingAndExplanation() {
        val quality = PhotoQuality(0f, 0.7f, 0f, 0f)
        val result = UnwantedPhotoScorer.score(quality, 0.9f, PhotoSettings(personalization = Priority.OFF))
        assertEquals(0.7f, result.value, 0f)
        assertEquals("Очень тёмный кадр", result.reason)
        assertEquals("Похожие фото вы обычно удаляете",
            UnwantedPhotoScorer.score(null, 0.5f, PhotoSettings()).reason)
        assertNull(UnwantedPhotoScorer.score(null, 0f, PhotoSettings()).reason)
    }

    @Test fun embeddingsRoundTripAndRejectInvalidData() {
        assertEquals(768, target.encode().size)
        assertEquals(1f, target.similarity(checkNotNull(PhotoEmbedding.decode(target.encode(), 768))), 0.00001f)
        assertNull(PhotoEmbedding.decode(byteArrayOf(0, 1), 768))
        assertNull(PhotoEmbedding.from(FloatArray(768)))
        assertNull(PhotoEmbedding.from(FloatArray(768) { Float.NaN }))
        assertNull(PhotoEmbedding.from(floatArrayOf(1f)))
    }

    @Test fun compactVectorsPreserveCosineSimilarity() {
        val a = FloatArray(768) { kotlin.math.sin(it * 0.13).toFloat() }
        val b = FloatArray(768) { a[it] * 0.8f + kotlin.math.cos(it * 0.17).toFloat() * 0.2f }
        val dot = a.indices.sumOf { a[it].toDouble() * b[it] }
        val expected = dot / kotlin.math.sqrt(a.sumOf { it.toDouble() * it } * b.sumOf { it.toDouble() * it })
        val encoded = checkNotNull(PhotoEmbedding.from(a))
        assertEquals(expected, encoded.similarity(checkNotNull(PhotoEmbedding.from(b))).toDouble(), 0.001)
        assertArrayEquals(encoded.encode(), PhotoEmbedding.decode(encoded.encode(), 768)!!.encode())
    }
}
