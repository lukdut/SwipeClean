package com.lukdut.swipeclean.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class MediaStoreSystemTrashTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver = context.contentResolver

    @Test fun onlyExplicitlyTrashedCandidatesAreRecoveredAndRestoredFilesAreExcluded() = runBlocking {
        val created = mutableListOf<Uri>()
        try {
            val visible = createPhoto().also(created::add)
            val trashed = createPhoto().also(created::add)
            val otherTrashed = createPhoto().also(created::add)
            for (uri in listOf(trashed, otherTrashed)) {
                assertEquals(1, resolver.update(uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null))
            }
            val repository = MediaStoreRepository(context)
            val trashedId = ContentUris.parseId(trashed)
            // Nonexistent IDs must not be treated as removed files, and a large candidate
            // collection must stay below SQLite's bind limit.
            val candidates = (1L..1_100L).map { -it } +
                listOf(ContentUris.parseId(visible), trashedId)
            assertEquals(setOf(trashedId), repository.loadSystemTrashedIds(candidates))
            assertTrue(repository.loadSystemTrashedIds(emptyList()).isEmpty())

            assertEquals(1, resolver.update(trashed,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) },
                Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }))
            assertTrue(repository.loadSystemTrashedIds(candidates).isEmpty())
        } finally {
            // These are synthetic app-owned files; no existing user media is changed.
            created.forEach { uri ->
                resolver.delete(uri, Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                })
            }
        }
    }

    private fun createPhoto(): Uri {
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "system-trash-query-${System.nanoTime()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SwipeCleanSystemTrashTest")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            checkNotNull(resolver.openOutputStream(uri)).use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } finally {
            bitmap.recycle()
        }
        return uri
    }
}
