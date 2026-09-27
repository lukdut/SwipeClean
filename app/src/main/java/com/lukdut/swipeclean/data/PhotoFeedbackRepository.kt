package com.lukdut.swipeclean.data

import androidx.room.withTransaction
import com.lukdut.swipeclean.data.db.AppDatabase
import com.lukdut.swipeclean.data.db.PhotoAnalysisEntity
import com.lukdut.swipeclean.data.db.PhotoFeedbackEntity
import com.lukdut.swipeclean.data.db.PhotoReviewEntity
import com.lukdut.swipeclean.data.db.PhotoReviewStatus

class PhotoFeedbackRepository(private val db: AppDatabase) {
    private val feedback = db.photoFeedbackDao()

    suspend fun review(photo: MediaPhoto, status: PhotoReviewStatus, now: Long = System.currentTimeMillis()) =
        db.withTransaction {
            db.photoReviewDao().upsert(PhotoReviewEntity(photo.id, status, now))
            // Preserve the actual producer version. Consumers filter by the active model.
            val cached = db.photoAnalysisDao().getById(photo.id)?.takeIf { it.matches(photo) &&
                it.embeddingVersion != null && PhotoEmbedding.decode(it.embedding, it.embedding?.size ?: 0) != null }
            val decision = if (status == PhotoReviewStatus.KEPT) FeedbackDecision.KEPT else FeedbackDecision.TRASH
            feedback.upsert(PhotoFeedbackEntity(photo.feedbackKey(), photo.id, decision,
                cached?.embedding, cached?.embeddingVersion, photo.ageDays(now), now))
            feedback.trim(decision.name)
        }

    suspend fun restore(ids: Collection<Long>) = db.withTransaction {
        db.photoReviewDao().deleteByIds(ids)
        feedback.undoTrash(ids)
    }

    suspend fun resetProgress() = db.withTransaction {
        db.photoReviewDao().deleteAll()
        feedback.undoAllTrash()
    }

    suspend fun saveAnalysis(batch: List<PhotoAnalysisEntity>) = db.withTransaction {
        db.photoAnalysisDao().upsertAll(batch)
        for (entry in batch) {
            if (entry.embeddingVersion != null && PhotoEmbedding.decode(entry.embedding, entry.embedding?.size ?: 0) != null) {
                val key = "${entry.mediaStoreId}:${entry.uri}:${entry.dateAdded}:${entry.dateModified}:${entry.size}"
                // UPDATE only: a restore or 'forget history' must never be undone by an in-flight analysis.
                feedback.attachEmbedding(key, checkNotNull(entry.embedding), checkNotNull(entry.embeddingVersion))
            }
        }
    }

    suspend fun deleted(photos: List<MediaPhoto>) = db.withTransaction {
        feedback.confirmDeleted(photos.map { it.feedbackKey() })
        feedback.trim(FeedbackDecision.DELETED.name)
        db.photoReviewDao().deleteByIds(photos.map { it.id })
        db.photoAnalysisDao().deleteByIds(photos.map { it.id })
    }
}
