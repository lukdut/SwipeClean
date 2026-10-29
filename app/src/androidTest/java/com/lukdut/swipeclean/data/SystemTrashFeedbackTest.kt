package com.lukdut.swipeclean.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lukdut.swipeclean.data.db.AppDatabase
import com.lukdut.swipeclean.data.db.PhotoAnalysisEntity
import com.lukdut.swipeclean.data.db.PhotoReviewStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemTrashFeedbackTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val photo = photo(1)
    private val embedding = checkNotNull(PhotoEmbedding.from(floatArrayOf(1f, 0f)))
    private val analysis = PhotoAnalysisEntity.from(photo,
        PhotoAnalysis(PhotoQuality(0f, 0f, 0f, 0f), embedding, "system-trash-test"))

    @Test fun confirmedSystemTrashPreservesReversibleEvidenceAndClearsTheAppBasket() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = PhotoFeedbackRepository(db)
            repository.saveAnalysis(listOf(analysis))
            repository.review(photo, PhotoReviewStatus.TRASH)
            repository.systemTrashed(listOf(photo))

            val sample = checkNotNull(db.photoFeedbackDao().getByKey(photo.feedbackKey()))
            assertEquals(FeedbackDecision.SYSTEM_TRASH, sample.decision)
            assertArrayEquals(analysis.embedding, sample.embedding)
            assertTrue(db.photoReviewDao().getAll().isEmpty())
            assertTrue(db.photoAnalysisDao().getAll().isEmpty())

            // No visible file is not proof of permanent deletion; a progress reset also
            // must not reinterpret an already-confirmed system operation.
            repository.reconcileSystemTrash(emptyList())
            repository.resetProgress()
            assertEquals(FeedbackDecision.SYSTEM_TRASH,
                db.photoFeedbackDao().getByKey(photo.feedbackKey())!!.decision)
        } finally {
            db.close()
        }
    }

    @Test fun restoredFilesForgetSystemTrashEvidenceWithoutChangingOtherDecisions() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = PhotoFeedbackRepository(db)
            val kept = photo(2)
            val permanentlyDeleted = photo(3)
            val stillInSystemTrash = photo(4)
            repository.review(photo, PhotoReviewStatus.TRASH)
            repository.systemTrashed(listOf(photo))
            repository.review(kept, PhotoReviewStatus.KEPT)
            repository.review(permanentlyDeleted, PhotoReviewStatus.TRASH)
            repository.deleted(listOf(permanentlyDeleted))
            repository.review(stillInSystemTrash, PhotoReviewStatus.TRASH)
            repository.systemTrashed(listOf(stillInSystemTrash))

            // A restore may update DATE_MODIFIED, so reconciliation uses the MediaStore ID.
            val restored = photo.copy(dateModified = photo.dateModified + 1)
            repository.reconcileSystemTrash(listOf(restored.id, kept.id, permanentlyDeleted.id))
            repository.saveAnalysis(listOf(analysis))

            assertNull(db.photoFeedbackDao().getByKey(photo.feedbackKey()))
            assertEquals(FeedbackDecision.KEPT,
                db.photoFeedbackDao().getByKey(kept.feedbackKey())!!.decision)
            assertEquals(FeedbackDecision.DELETED,
                db.photoFeedbackDao().getByKey(permanentlyDeleted.feedbackKey())!!.decision)
            assertEquals(FeedbackDecision.SYSTEM_TRASH,
                db.photoFeedbackDao().getByKey(stillInSystemTrash.feedbackKey())!!.decision)
            assertEquals(listOf(kept.id), db.photoReviewDao().getAll().map { it.mediaStoreId })

            repository.review(restored, PhotoReviewStatus.KEPT)
            assertNotNull(db.photoFeedbackDao().getByKey(restored.feedbackKey()))
            assertNull(db.photoFeedbackDao().getByKey(photo.feedbackKey()))
        } finally {
            db.close()
        }
    }

    @Test fun confirmingSystemTrashCannotRecreateForgottenFeedback() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = PhotoFeedbackRepository(db)
            repository.review(photo, PhotoReviewStatus.TRASH)
            db.photoFeedbackDao().deleteAll()
            repository.systemTrashed(listOf(photo))
            repository.saveAnalysis(listOf(analysis))

            assertNull(db.photoFeedbackDao().getByKey(photo.feedbackKey()))
            assertTrue(db.photoReviewDao().getAll().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test fun lostCallbackRecoveryOnlyConfirmsObservedTrashAndDoesNotRecreateHistory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = PhotoFeedbackRepository(db)
            val unconfirmed = photo(2)
            val forgotten = photo(3)
            val kept = photo(4)
            repository.saveAnalysis(listOf(analysis))
            repository.review(photo, PhotoReviewStatus.TRASH)
            repository.review(forgotten, PhotoReviewStatus.TRASH)
            db.photoFeedbackDao().deleteAll()
            repository.review(photo, PhotoReviewStatus.TRASH)
            repository.review(unconfirmed, PhotoReviewStatus.TRASH)
            repository.review(kept, PhotoReviewStatus.KEPT)

            // Only the files explicitly observed in system trash are eligible for recovery.
            repository.reconcilePendingSystemTrash(listOf(photo.id, forgotten.id, kept.id))
            assertEquals(FeedbackDecision.SYSTEM_TRASH,
                db.photoFeedbackDao().getByKey(photo.feedbackKey())!!.decision)
            assertArrayEquals(analysis.embedding,
                db.photoFeedbackDao().getByKey(photo.feedbackKey())!!.embedding)
            assertNull(db.photoFeedbackDao().getByKey(forgotten.feedbackKey()))
            assertEquals(FeedbackDecision.TRASH,
                db.photoFeedbackDao().getByKey(unconfirmed.feedbackKey())!!.decision)
            assertEquals(FeedbackDecision.KEPT,
                db.photoFeedbackDao().getByKey(kept.feedbackKey())!!.decision)
            assertEquals(setOf(unconfirmed.id, kept.id),
                db.photoReviewDao().getAll().map { it.mediaStoreId }.toSet())
            assertNull(db.photoAnalysisDao().getById(photo.id))

            repository.reconcileSystemTrash(listOf(photo.id, forgotten.id))
            repository.saveAnalysis(listOf(analysis))
            assertNull(db.photoFeedbackDao().getByKey(photo.feedbackKey()))
        } finally {
            db.close()
        }
    }

    private fun photo(id: Long) = MediaPhoto(id,
        Uri.parse("content://media/external/images/media/$id"), "test-$id", 100, 1234, 200)
}
