package com.lukdut.swipeclean.data.db

import androidx.room.Embedded
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.lukdut.swipeclean.data.MediaPhoto
import com.lukdut.swipeclean.data.PhotoQuality
import com.lukdut.swipeclean.data.PhotoQualityCalculator
import com.lukdut.swipeclean.data.model.ModelSpec
import com.lukdut.swipeclean.data.PhotoEmbedding
import com.lukdut.swipeclean.data.PhotoAnalysis

@Entity(tableName = "photo_analysis")
data class PhotoAnalysisEntity(
    @PrimaryKey val mediaStoreId: Long,
    val uri: String,
    val dateAdded: Long,
    val dateModified: Long,
    val size: Long,
    val analyzerVersion: Int,
    @Embedded val quality: PhotoQuality,
    @ColumnInfo(defaultValue = "NULL") val embedding: ByteArray? = null,
    @ColumnInfo(defaultValue = "NULL") val embeddingVersion: String? = null
) {
    fun matches(photo: MediaPhoto): Boolean = mediaStoreId == photo.id && uri == photo.uri.toString() &&
        dateAdded == photo.dateAdded && dateModified == photo.dateModified && size == photo.size &&
        analyzerVersion == PhotoQualityCalculator.VERSION

    fun currentEmbedding(model: ModelSpec): PhotoEmbedding? =
        if (embeddingVersion == model.embeddingVersion) PhotoEmbedding.decode(embedding, model.dimensions) else null

    fun isComplete(photo: MediaPhoto, model: ModelSpec): Boolean = matches(photo) && currentEmbedding(model) != null

    override fun equals(other: Any?): Boolean = other is PhotoAnalysisEntity &&
        mediaStoreId == other.mediaStoreId && uri == other.uri && dateAdded == other.dateAdded &&
        dateModified == other.dateModified && size == other.size && analyzerVersion == other.analyzerVersion &&
        quality == other.quality && embeddingVersion == other.embeddingVersion && embedding.contentEquals(other.embedding)

    override fun hashCode(): Int = java.util.Objects.hash(mediaStoreId, uri, dateAdded, dateModified, size,
        analyzerVersion, quality, embeddingVersion, embedding.contentHashCode())

    companion object {
        fun from(photo: MediaPhoto, quality: PhotoQuality) = PhotoAnalysisEntity(
            photo.id, photo.uri.toString(), photo.dateAdded, photo.dateModified, photo.size,
            PhotoQualityCalculator.VERSION, quality
        )

        fun from(photo: MediaPhoto, analysis: PhotoAnalysis) = from(photo, analysis.quality).copy(
            embedding = analysis.embedding.encode(), embeddingVersion = analysis.embeddingVersion
        )
    }
}
