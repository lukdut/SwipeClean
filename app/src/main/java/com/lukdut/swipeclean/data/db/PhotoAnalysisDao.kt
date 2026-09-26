package com.lukdut.swipeclean.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface PhotoAnalysisDao {
    @Query("SELECT * FROM photo_analysis")
    suspend fun getAll(): List<PhotoAnalysisEntity>

    @Upsert
    suspend fun upsert(entity: PhotoAnalysisEntity)

    @Query("DELETE FROM photo_analysis WHERE mediaStoreId IN (:ids)")
    suspend fun deleteByIds(ids: Collection<Long>)
}
