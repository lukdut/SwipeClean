package com.lukdut.swipeclean.ui

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
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
import com.lukdut.swipeclean.data.FeedbackDecision
import com.lukdut.swipeclean.data.MediaStoreRepository
import com.lukdut.swipeclean.data.PhotoAnalysis
import com.lukdut.swipeclean.data.PhotoEmbedding
import com.lukdut.swipeclean.data.PhotoQuality
import com.lukdut.swipeclean.data.PhotoSettings
import com.lukdut.swipeclean.data.SettingsRepository
import com.lukdut.swipeclean.data.SortOrder
import com.lukdut.swipeclean.data.TestModelFixture
import com.lukdut.swipeclean.data.db.AppDatabase
import com.lukdut.swipeclean.data.db.PhotoAnalysisEntity
import com.lukdut.swipeclean.data.db.PhotoFeedbackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class SwipeCaptionTest {
    @Test fun feedbackRefreshDoesNotBlankTheNextCaptionAndUnrelatedPhotoClearsItImmediately() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE)
        val model = runBlocking { TestModelFixture.install(context) }.spec
        val database = AppDatabase.getInstance(context)
        val feedback = database.photoFeedbackDao()
        val originalHistory = runBlocking { feedback.observeAll().first() }
        val settings = SettingsRepository(context.getSharedPreferences("photo_settings", 0))
        val originalSettings = settings.load()
        val resolver = context.contentResolver
        val uris = mutableListOf<Uri>()
        val photoIds = mutableListOf<Long>()
        val observations = CopyOnWriteArrayList<SwipeUiState>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var scenario: ActivityScenario<MainActivity>? = null
        val reason = "Похожие фото вы обычно удаляете"
        fun vector(index: Int) = checkNotNull(PhotoEmbedding.from(FloatArray(model.dimensions) {
            if (it == index) 1f else 0f
        }))

        try {
            settings.save(PhotoSettings())
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.GRAY)
                repeat(3) { index ->
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "caption-test-${System.nanoTime()}-$index.png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SwipeCleanCaptionTest")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                    uris += uri
                    checkNotNull(resolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                }
            } finally { bitmap.recycle() }
            runBlocking {
                feedback.deleteAll()
                repeat(4) { index ->
                    feedback.upsert(PhotoFeedbackEntity("caption-history-$index", -1L - index,
                        FeedbackDecision.DELETED, vector(0).encode(), model.embeddingVersion, null,
                        System.currentTimeMillis()))
                }
                val photos = MediaStoreRepository(context).loadAllPhotos().filter { it.uri in uris }
                assertEquals(3, photos.size)
                photoIds += photos.map { it.id }
                database.photoAnalysisDao().upsertAll(photos.map { photo ->
                    // The newest two photos match the deletion history; the oldest has no reason.
                    val embedding = vector(if (photo.uri == uris.first()) 1 else 0)
                    PhotoAnalysisEntity.from(photo, PhotoAnalysis(PhotoQuality(0f, 0f, 0f, 0f),
                        embedding, model.embeddingVersion))
                })
            }

            val activity = ActivityScenario.launch(MainActivity::class.java)
            scenario = activity
            lateinit var viewModel: AppViewModel
            activity.onActivity { viewModel = ViewModelProvider(it)[AppViewModel::class.java] }
            await { !viewModel.isLoading.value && viewModel.hasAnalysisResults.value && viewModel.feedbackCount.value == 4 }
            activity.onActivity {
                viewModel.setSortOrder(SortOrder.ByPotentiallyUnwanted)
                viewModel.setReviewVisible(true)
            }
            await { viewModel.swipeUiState.value.priorityReason == reason }
            val nextId = checkNotNull(viewModel.swipeUiState.value.nextPhoto).id
            scope.launch { viewModel.swipeUiState.collect { observations += it } }
            await { observations.isNotEmpty() }

            activity.onActivity { viewModel.keep() }
            await { viewModel.swipeUiState.value.currentPhoto?.id == nextId && viewModel.feedbackCount.value == 5 }
            // Cover both the Room emission and the 150 ms debounced ranking that follows a swipe.
            SystemClock.sleep(400)
            val nextStates = observations.filter { it.currentPhoto?.id == nextId }
            assertTrue(nextStates.isNotEmpty())
            assertTrue("The caption must not disappear while feedback is recalculated",
                nextStates.all { it.priorityReason == reason })

            activity.onActivity {
                viewModel.keep()
                val state = viewModel.swipeUiState.value
                assertNotNull(state.currentPhoto)
                assertNotEquals(nextId, state.currentPhoto!!.id)
                assertNull("Do not carry the previous caption onto a photo without a reason", state.priorityReason)
            }
            await { viewModel.feedbackCount.value == 6 }
        } finally {
            scope.cancel()
            scenario?.close()
            runBlocking {
                database.photoReviewDao().deleteByIds(photoIds)
                database.photoAnalysisDao().deleteByIds(photoIds)
                feedback.deleteAll()
                originalHistory.forEach { feedback.upsert(it) }
            }
            uris.forEach { resolver.delete(it, null, null) }
            settings.save(originalSettings)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for caption state" }
            SystemClock.sleep(20)
        }
    }
}
