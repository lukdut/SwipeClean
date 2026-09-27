package com.lukdut.swipeclean.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoAnalysisDao {
    @Query("SELECT * FROM photo_analysis WHERE mediaStoreId = :id")
    suspend fun getById(id: Long): PhotoAnalysisEntity?

    @Query("SELECT * FROM photo_analysis")
    suspend fun getAll(): List<PhotoAnalysisEntity>

    @Query("SELECT * FROM photo_analysis")
    fun observeAll(): Flow<List<PhotoAnalysisEntity>>

    @Upsert
    suspend fun upsertAll(entities: List<PhotoAnalysisEntity>)

    @Query("DELETE FROM photo_analysis WHERE mediaStoreId IN (:ids)")
    suspend fun deleteByIds(ids: Collection<Long>)
}
