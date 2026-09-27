package com.lukdut.swipeclean.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lukdut.swipeclean.data.FeedbackDecision

/** No foreign key: the feature snapshot must outlive the original photo and its analysis cache. */
@Entity(tableName = "photo_feedback", indices = [Index("mediaStoreId")])
data class PhotoFeedbackEntity(
    @PrimaryKey val photoKey: String,
    val mediaStoreId: Long,
    val decision: FeedbackDecision,
    val embedding: ByteArray?,
    val embeddingVersion: String?,
    val ageDays: Float?,
    val decidedAt: Long
)
