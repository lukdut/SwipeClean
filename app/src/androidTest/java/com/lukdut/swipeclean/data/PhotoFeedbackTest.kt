package com.lukdut.swipeclean.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lukdut.swipeclean.data.db.AppDatabase
import com.lukdut.swipeclean.data.db.PhotoAnalysisEntity
import com.lukdut.swipeclean.data.db.PhotoReviewStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoFeedbackTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val photo = MediaPhoto(1, Uri.parse("content://media/external/images/media/1"), "test", 100, 1234, 200,
        dateTaken = 1_000_000)
    private val model = TestModelFixture.spec(context)
    private val embedding = checkNotNull(PhotoEmbedding.from(FloatArray(model.dimensions) {
        if (it == 0) 1f else 0f
    }))
    private val analysis = PhotoAnalysisEntity.from(photo, PhotoAnalysis(PhotoQuality(0f, 0f, 0f, 0f), embedding, model.embeddingVersion))

    @Test fun deletingThePhotoPreservesItsFeaturesAndConfirmedDecision() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = PhotoFeedbackRepository(db)
            repository.saveAnalysis(listOf(analysis))
            repository.review(photo, PhotoReviewStatus.TRASH, 87_400_000)
            repository.deleted(listOf(photo))
            val sample = checkNotNull(db.photoFeedbackDao().getByKey(photo.feedbackKey()))
            assertEquals(FeedbackDecision.DELETED, sample.decision)
            assertEquals(1f, sample.ageDays!!, 0.001f)
            assertArrayEquals(analysis.embedding, sample.embedding)
            assertTrue(db.photoAnalysisDao().getAll().isEmpty())
            assertTrue(db.photoReviewDao().getIdsByStatus(PhotoReviewStatus.TRASH).isEmpty())
        } finally { db.close() }
    }

    @Test fun lateAnalysisEnrichesPendingDecisionsButCannotResurrectRestoredOrForgottenOnes() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = PhotoFeedbackRepository(db)
            repository.review(photo, PhotoReviewStatus.KEPT)
            assertNull(db.photoFeedbackDao().getByKey(photo.feedbackKey())!!.embedding)
            repository.saveAnalysis(listOf(analysis))
            assertArrayEquals(analysis.embedding, db.photoFeedbackDao().getByKey(photo.feedbackKey())!!.embedding)
            repository.review(photo, PhotoReviewStatus.TRASH)
            repository.restore(listOf(photo.id))
            repository.saveAnalysis(listOf(analysis))
            assertNull(db.photoFeedbackDao().getByKey(photo.feedbackKey()))
            repository.review(photo, PhotoReviewStatus.KEPT)
            db.photoFeedbackDao().deleteAll()
            repository.saveAnalysis(listOf(analysis))
            assertNull(db.photoFeedbackDao().getByKey(photo.feedbackKey()))
            assertEquals(listOf(photo.id), db.photoReviewDao().getIdsByStatus(PhotoReviewStatus.KEPT))
        } finally { db.close() }
    }

    @Test fun repeatedReviewReplacesASampleAndResetCancelsBasketEvidence() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = PhotoFeedbackRepository(db)
            repository.saveAnalysis(listOf(analysis))
            repeat(4) { repository.review(photo, PhotoReviewStatus.KEPT) }
            assertEquals(1, db.photoFeedbackDao().observeAll().first().size)
            repository.resetProgress()
            assertEquals(1, db.photoFeedbackDao().observeAll().first().size)
            repository.review(photo, PhotoReviewStatus.TRASH)
            repository.resetProgress()
            assertTrue(db.photoFeedbackDao().observeAll().first().isEmpty())
            assertTrue(db.photoAnalysisDao().getById(photo.id)!!.isComplete(photo, model))
        } finally { db.close() }
    }

    @Test fun changedMediaAndObsoleteModelsCannotSupplyTrainingFeatures() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = PhotoFeedbackRepository(db)
            repository.saveAnalysis(listOf(analysis))
            val changed = photo.copy(dateModified = 201)
            repository.review(changed, PhotoReviewStatus.TRASH)
            assertNull(db.photoFeedbackDao().getByKey(changed.feedbackKey())!!.embedding)
            assertNull(analysis.copy(embeddingVersion = "old-model").currentEmbedding(model))
            assertFalse(analysis.copy(embedding = byteArrayOf(1)).isComplete(photo, model))
            assertNull(photo.copy(dateTaken = 0).ageDays(System.currentTimeMillis()))
        } finally { db.close() }
    }

    @Test fun modelUpdateInvalidatesCacheEvenIfVectorDimensionsStayTheSame() {
        assertTrue(analysis.isComplete(photo, model))
        val replacement = model.copy(sha256 = "a".repeat(64))
        assertFalse(analysis.isComplete(photo, replacement))
        assertNull(analysis.currentEmbedding(replacement))
        assertFalse(analysis.isComplete(photo, model.copy(mean = listOf(0.4f, 0.4f, 0.4f))))
    }
}
