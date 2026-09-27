package com.lukdut.swipeclean.data.db

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

enum class PhotoReviewStatus { KEPT, TRASH }

@Entity(tableName = "photo_review")
data class PhotoReviewEntity(
    @PrimaryKey val mediaStoreId: Long,
    val status: PhotoReviewStatus,
    @ColumnInfo(defaultValue = "0") val reviewedAt: Long = 0
)
