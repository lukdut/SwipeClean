package com.lukdut.swipeclean.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface PhotoReviewDao {

    @Query("SELECT mediaStoreId FROM photo_review WHERE status = :status")
    suspend fun getIdsByStatus(status: PhotoReviewStatus): List<Long>

    @Upsert
    suspend fun upsert(entity: PhotoReviewEntity)

    @Query("DELETE FROM photo_review WHERE mediaStoreId = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM photo_review WHERE mediaStoreId IN (:ids)")
    suspend fun deleteByIds(ids: Collection<Long>)

    @Query("DELETE FROM photo_review WHERE status = :status")
    suspend fun deleteAllByStatus(status: PhotoReviewStatus)

    /** Удаляет записи о фото, которых больше нет в MediaStore. */
    @Query("DELETE FROM photo_review WHERE mediaStoreId NOT IN (:existingIds)")
    suspend fun deleteOrphans(existingIds: List<Long>)
}
