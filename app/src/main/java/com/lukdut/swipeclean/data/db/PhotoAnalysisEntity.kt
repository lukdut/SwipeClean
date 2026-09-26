package com.lukdut.swipeclean.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.lukdut.swipeclean.data.MediaPhoto
import com.lukdut.swipeclean.data.PhotoQuality
import com.lukdut.swipeclean.data.PhotoQualityCalculator

@Entity(tableName = "photo_analysis")
data class PhotoAnalysisEntity(
    @PrimaryKey val mediaStoreId: Long,
    val uri: String,
    val dateAdded: Long,
    val dateModified: Long,
    val size: Long,
    val analyzerVersion: Int,
    @Embedded val quality: PhotoQuality
) {
    fun matches(photo: MediaPhoto): Boolean = mediaStoreId == photo.id && uri == photo.uri.toString() &&
        dateAdded == photo.dateAdded && dateModified == photo.dateModified && size == photo.size &&
        analyzerVersion == PhotoQualityCalculator.VERSION

    companion object {
        fun from(photo: MediaPhoto, quality: PhotoQuality) = PhotoAnalysisEntity(
            photo.id, photo.uri.toString(), photo.dateAdded, photo.dateModified, photo.size,
            PhotoQualityCalculator.VERSION, quality
        )
    }
}
