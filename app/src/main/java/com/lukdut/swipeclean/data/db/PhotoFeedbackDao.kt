package com.lukdut.swipeclean.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoFeedbackDao {
    @Query("SELECT * FROM photo_feedback ORDER BY decidedAt DESC, photoKey")
    fun observeAll(): Flow<List<PhotoFeedbackEntity>>

    @Query("SELECT * FROM photo_feedback WHERE photoKey = :key")
    suspend fun getByKey(key: String): PhotoFeedbackEntity?

    @Upsert
    suspend fun upsert(feedback: PhotoFeedbackEntity)

    @Query("UPDATE photo_feedback SET embedding = :embedding, embeddingVersion = :version WHERE photoKey = :key")
    suspend fun attachEmbedding(key: String, embedding: ByteArray, version: String)

    @Query("DELETE FROM photo_feedback WHERE mediaStoreId IN (:ids) AND decision = 'TRASH'")
    suspend fun undoTrash(ids: Collection<Long>)

    @Query("DELETE FROM photo_feedback WHERE decision = 'TRASH'")
    suspend fun undoAllTrash()

    @Query("UPDATE photo_feedback SET decision = 'DELETED' WHERE photoKey IN (:keys) AND decision = 'TRASH'")
    suspend fun confirmDeleted(keys: Collection<String>)

    @Query("DELETE FROM photo_feedback")
    suspend fun deleteAll()

    // Keep both outcomes represented. Pending decisions are retained for a later manual analysis.
    @Query("DELETE FROM photo_feedback WHERE decision = :decision AND photoKey NOT IN " +
        "(SELECT photoKey FROM photo_feedback WHERE decision = :decision ORDER BY decidedAt DESC, photoKey LIMIT 2000)")
    suspend fun trim(decision: String)
}
