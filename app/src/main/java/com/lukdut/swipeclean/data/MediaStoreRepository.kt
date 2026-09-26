package com.lukdut.swipeclean.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaStoreRepository(private val context: Context) {

    suspend fun loadAllPhotos(): List<MediaPhoto> =
        withContext(Dispatchers.IO) {
        val photos = mutableListOf<MediaPhoto>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                photos.add(
                    MediaPhoto(
                        id = id,
                        uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                        ),
                        displayName = cursor.getString(nameCol) ?: "",
                        dateAdded = cursor.getLong(dateCol),
                        size = cursor.getLong(sizeCol),
                        dateModified = cursor.getLong(modifiedCol)
                    )
                )
            }
        }
        photos
    }
}
