package com.lukdut.swipeclean.data

import android.net.Uri

data class MediaPhoto(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateAdded: Long,
    val size: Long,
    val dateModified: Long = 0,
    val dateTaken: Long = 0
)

/** Includes the media revision so a reused MediaStore id cannot inherit an old decision. */
fun MediaPhoto.feedbackKey(): String = "$id:$uri:$dateAdded:$dateModified:$size"

fun MediaPhoto.ageDays(atMillis: Long): Float? = dateTaken.takeIf { it > 0 && it <= atMillis }
    ?.let { (atMillis - it) / 86_400_000f }
