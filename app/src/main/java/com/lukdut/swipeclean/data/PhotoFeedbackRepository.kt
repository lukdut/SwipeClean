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

    suspend fun undoReview(photo: MediaPhoto) = db.withTransaction {
        db.photoReviewDao().deleteById(photo.id)
        feedback.undoReview(photo.feedbackKey())
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

    suspend fun systemTrashed(photos: List<MediaPhoto>) = db.withTransaction {
        feedback.confirmSystemTrash(photos.map { it.feedbackKey() })
        feedback.trim(FeedbackDecision.SYSTEM_TRASH.name)
        db.photoReviewDao().deleteByIds(photos.map { it.id })
        db.photoAnalysisDao().deleteByIds(photos.map { it.id })
    }

    suspend fun reconcilePendingSystemTrash(ids: Collection<Long>) = db.withTransaction {
        if (ids.isEmpty()) return@withTransaction
        // Recover an interrupted result callback only from rows positively identified as
        // trashed by MediaStore. A later explicit review takes precedence over recovery.
        val pending = ids.toSet().intersect(
            db.photoReviewDao().getIdsByStatus(PhotoReviewStatus.TRASH).toSet())
        pending.chunked(500).forEach { batch ->
            feedback.confirmSystemTrashByIds(batch)
            db.photoReviewDao().deleteByIds(batch)
            db.photoAnalysisDao().deleteByIds(batch)
        }
        if (pending.isNotEmpty()) feedback.trim(FeedbackDecision.SYSTEM_TRASH.name)
    }

    suspend fun reconcileSystemTrash(availableIds: Collection<Long>) = db.withTransaction {
        // Normal MediaStore queries exclude trashed files. A visible file has returned from
        // system trash; the earlier decision must no longer influence recommendations.
        // Match the ID because moving out of trash can change the file's revision key.
        // Keep below the SQLite bind limit even for galleries with thousands of files.
        availableIds.chunked(500).forEach { feedback.undoSystemTrash(it) }
    }
}
