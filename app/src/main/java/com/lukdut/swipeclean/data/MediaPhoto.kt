package com.lukdut.swipeclean.data

import android.net.Uri

data class MediaPhoto(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateAdded: Long,
    val size: Long,
    val dateModified: Long = 0
)
