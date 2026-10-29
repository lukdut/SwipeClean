package com.lukdut.swipeclean.data

import android.content.ContentUris
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaStoreRepository(private val context: Context) {

    suspend fun loadSystemTrashedIds(candidateIds: Collection<Long>): Set<Long> =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || candidateIds.isEmpty()) {
                return@withContext emptySet()
            }
            val trashed = mutableSetOf<Long>()
            for (batch in candidateIds.distinct().chunked(500)) {
                val candidates = batch.toSet()
                val arguments = Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "${MediaStore.Images.Media._ID} IN (${batch.joinToString { "?" }})")
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        batch.map { it.toString() }.toTypedArray())
                }
                try {
                    context.contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.Images.Media._ID, MediaStore.MediaColumns.IS_TRASHED),
                        arguments,
                        null
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val trashedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            // An absent or unreadable row proves nothing about its deletion state.
                            if (id in candidates && cursor.getInt(trashedCol) == 1) trashed.add(id)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Recovery is best effort: permission/provider errors leave the original
                    // pending decisions intact and must not prevent normal gallery loading.
                }
            }
            trashed
        }

    suspend fun loadAllPhotos(): List<MediaPhoto> =
        withContext(Dispatchers.IO) {
        val photos = mutableListOf<MediaPhoto>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_TAKEN
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
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)

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
                        dateModified = cursor.getLong(modifiedCol),
                        dateTaken = cursor.getLong(takenCol)
                    )
                )
            }
        }
        photos
    }
}
