package com.lukdut.swipeclean.ui

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.lukdut.swipeclean.MainActivity
import com.lukdut.swipeclean.data.MediaPhoto
import com.lukdut.swipeclean.data.PhotoFeedbackRepository
import com.lukdut.swipeclean.data.SortOrder
import com.lukdut.swipeclean.data.db.AppDatabase
import com.lukdut.swipeclean.data.feedbackKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class UndoHistoryTest {
    @Test
    fun lastTenDecisionsCanBeUndoneAndReviewingAgainPreservesEarlierHistory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val db = AppDatabase.getInstance(context)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE)
        val uris = mutableListOf<Uri>()
        val reviewed = mutableListOf<MediaPhoto>()
        var scenario: ActivityScenario<MainActivity>? = null
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val bytes = try {
            ByteArrayOutputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                it.toByteArray()
            }
        } finally { bitmap.recycle() }
        try {
            repeat(13) { index ->
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "undo-${System.nanoTime()}-$index.png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SwipeCleanUndoTest")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }))
                uris += uri
                checkNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            val ids = uris.map { ContentUris.parseId(it) }.toSet()
            val activity = ActivityScenario.launch(MainActivity::class.java)
            scenario = activity
            lateinit var viewModel: AppViewModel
            activity.onActivity {
                viewModel = ViewModelProvider(it)[AppViewModel::class.java]
                viewModel.setSortOrder(SortOrder.ByDateDesc)
            }
            await { !viewModel.isLoading.value && viewModel.swipeUiState.value.currentPhoto != null }
            activity.onActivity {
                repeat(12) { index ->
                    val photo = checkNotNull(viewModel.swipeUiState.value.currentPhoto)
                    check(photo.id in ids) { "Expected a test photo" }
                    reviewed += photo
                    if (index % 2 == 0) viewModel.keep() else viewModel.markForDeletion()
                }
            }
            for (index in 11 downTo 2) {
                activity.onActivity { viewModel.undoLastReview() }
                await { !viewModel.swipeUiState.value.undoing }
                val state = viewModel.swipeUiState.value
                assertNull(state.undoError)
                assertEquals(reviewed[index].id, state.currentPhoto?.id)
                assertEquals(index, state.progress.first)
                assertEquals(index > 2, state.canUndo)
                if (index == 11) {
                    // A corrected swipe is the newest action; older undo entries must remain.
                    activity.onActivity { viewModel.keep(); viewModel.undoLastReview() }
                    await { !viewModel.swipeUiState.value.undoing }
                    assertEquals(reviewed[index].id, viewModel.swipeUiState.value.currentPhoto?.id)
                    assertTrue(viewModel.swipeUiState.value.canUndo)
                }
            }
            activity.onActivity { viewModel.undoLastReview() }
            assertEquals(reviewed[2].id, viewModel.swipeUiState.value.currentPhoto?.id)
            runBlocking {
                assertEquals(reviewed.take(2).map { it.id }.toSet(),
                    db.photoReviewDao().getAll().filter { it.mediaStoreId in ids }.map { it.mediaStoreId }.toSet())
                reviewed.drop(2).forEach { assertNull(db.photoFeedbackDao().getByKey(it.feedbackKey())) }
            }
        } finally {
            scenario?.close()
            runBlocking { reviewed.forEach { PhotoFeedbackRepository(db).undoReview(it) } }
            uris.forEach { resolver.delete(it, null, null) }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for undo" }
            SystemClock.sleep(10)
        }
    }
}
