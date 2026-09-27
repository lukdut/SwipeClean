package com.lukdut.swipeclean.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.PowerManager
import android.provider.MediaStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.lukdut.swipeclean.MainActivity
import com.lukdut.swipeclean.analysis.AnalysisCoordinator
import com.lukdut.swipeclean.analysis.PhotoAnalysisService
import com.lukdut.swipeclean.data.SortOrder
import com.lukdut.swipeclean.data.TestModelFixture
import com.lukdut.swipeclean.data.db.AppDatabase
import com.lukdut.swipeclean.data.db.PhotoReviewStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class AnalysisLifecycleTest {
    @Test
    fun backgroundAnalysisPausesFromNotificationAndResumesManuallyUsingSavedResults() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        runBlocking { TestModelFixture.install(context) }
        val resolver = context.contentResolver
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName,
                Manifest.permission.POST_NOTIFICATIONS)
        }
        val uris = mutableListOf<Uri>()
        var scenario: ActivityScenario<MainActivity>? = null
        val bitmap = Bitmap.createBitmap(3072, 3072, Bitmap.Config.ARGB_8888)
        val bytes = try {
            bitmap.eraseColor(Color.BLACK)
            ByteArrayOutputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                it.toByteArray()
            }
        } finally { bitmap.recycle() }
        try {
            // Enough files to interrupt a real decoding pass before it completes.
            repeat(128) { index ->
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "analysis-test-${System.nanoTime()}-$index.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SwipeCleanLifecycleTest")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                uris += uri
                checkNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            val activityScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = activityScenario
            lateinit var viewModel: AppViewModel
            activityScenario.onActivity { viewModel = ViewModelProvider(it)[AppViewModel::class.java] }
            await { !viewModel.isLoading.value && viewModel.currentPhoto.value != null }
            val initial = viewModel.analysisProgress.value
            activityScenario.onActivity {
                viewModel.setSortOrder(SortOrder.ByDateDesc)
                viewModel.setSortOrder(SortOrder.ByPotentiallyUnwanted)
            }
            SystemClock.sleep(150)
            assertFalse(viewModel.analysisProgress.value.running)
            assertEquals(initial.analyzed, viewModel.analysisProgress.value.analyzed)

            activityScenario.onActivity {
                viewModel.startAnalysis()
                viewModel.startAnalysis() // Duplicate taps must not start another pass.
            }
            await { viewModel.analysisProgress.value.running }
            activityScenario.moveToState(Lifecycle.State.CREATED)
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
            await { !context.getSystemService(PowerManager::class.java).isInteractive }
            val beforeScreenOff = viewModel.analysisProgress.value.analyzed
            await {
                val progress = viewModel.analysisProgress.value
                check(progress.error == null) { "Analysis failed: $progress" }
                progress.analyzed > beforeScreenOff
            }
            assertTrue("Analysis should continue with the activity stopped", viewModel.analysisProgress.value.running)
            val notifications = context.getSystemService(NotificationManager::class.java)
            val notification = notifications.activeNotifications.single {
                it.id == PhotoAnalysisService.NOTIFICATION_ID
            }
            notification.notification.actions.single { it.title.toString() == "Пауза" }.actionIntent.send()
            await { !viewModel.analysisProgress.value.running }
            val paused = viewModel.analysisProgress.value
            assertFalse(paused.running)
            assertTrue("Expected an interrupted pass", paused.remaining > 0)
            val savedAtPause = runBlocking { AppDatabase.getInstance(context).photoAnalysisDao().getAll().size }
            assertEquals(paused.analyzed, savedAtPause)
            await { notifications.activeNotifications.none { it.id == PhotoAnalysisService.NOTIFICATION_ID } }
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            activityScenario.moveToState(Lifecycle.State.RESUMED)
            SystemClock.sleep(200)
            assertEquals(paused, viewModel.analysisProgress.value)

            activityScenario.recreate()
            activityScenario.onActivity { viewModel = ViewModelProvider(it)[AppViewModel::class.java] }
            await { !viewModel.isLoading.value && viewModel.currentPhoto.value != null }
            assertFalse(viewModel.analysisProgress.value.running)
            assertEquals(paused.analyzed, viewModel.analysisProgress.value.analyzed)
            activityScenario.onActivity { viewModel.startAnalysis() }
            await { viewModel.analysisProgress.value.running }
            // Destroying the activity and its ViewModel must not own/cancel the service job.
            activityScenario.close()
            scenario = null
            await { AnalysisCoordinator.progress.value?.running == false }
            val finished = checkNotNull(AnalysisCoordinator.progress.value)
            assertTrue(finished.analyzed >= uris.size)
            assertEquals(finished.total, finished.analyzed)
            assertEquals(0, finished.skipped)
            assertEquals(null, finished.error)
            val completedScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = completedScenario
            completedScenario.onActivity { viewModel = ViewModelProvider(it)[AppViewModel::class.java] }
            await { !viewModel.isLoading.value && viewModel.currentPhoto.value != null }
            assertEquals(finished.analyzed, viewModel.analysisProgress.value.analyzed)
            assertFalse(viewModel.analysisProgress.value.running)
            assertTrue(viewModel.hasAnalysisResults.value)

            val database = AppDatabase.getInstance(context)
            val cachedBeforeReset = runBlocking { database.photoAnalysisDao().getAll() }.associateBy { it.mediaStoreId }
            val settingsBeforeReset = viewModel.settings.value
            completedScenario.onActivity {
                viewModel.keep()
                viewModel.markForDeletion()
                // Reset while the two swipe decisions are still being persisted.
                viewModel.resetProgress()
            }
            await { viewModel.progressReset.value.completed }
            await { viewModel.swipeProgress.value == (0 to finished.total) && viewModel.markedCount.value == 0 }
            assertEquals(settingsBeforeReset, viewModel.settings.value)
            assertEquals(finished.analyzed, viewModel.analysisProgress.value.analyzed)
            assertFalse(viewModel.analysisProgress.value.running)
            assertTrue(viewModel.hasAnalysisResults.value)
            runBlocking {
                assertTrue(database.photoReviewDao().getIdsByStatus(PhotoReviewStatus.KEPT).isEmpty())
                assertTrue(database.photoReviewDao().getIdsByStatus(PhotoReviewStatus.TRASH).isEmpty())
                assertEquals(cachedBeforeReset, database.photoAnalysisDao().getAll().associateBy { it.mediaStoreId })
            }

            completedScenario.close()
            val reopened = ActivityScenario.launch(MainActivity::class.java)
            scenario = reopened
            reopened.onActivity { viewModel = ViewModelProvider(it)[AppViewModel::class.java] }
            await { !viewModel.isLoading.value && viewModel.currentPhoto.value != null }
            assertEquals(0 to finished.total, viewModel.swipeProgress.value)
            assertEquals(0, viewModel.markedCount.value)
            assertEquals(settingsBeforeReset, viewModel.settings.value)
            assertEquals(finished.analyzed, viewModel.analysisProgress.value.analyzed)
            assertFalse(viewModel.analysisProgress.value.running)
        } finally {
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            runBlocking {
                withContext(Dispatchers.Main) {
                    AnalysisCoordinator.pauseAndJoin()
                    AnalysisCoordinator.clearFinishedProgress()
                }
            }
            scenario?.close()
            uris.forEach { resolver.delete(it, null, null) }
        }
    }

    private fun await(condition: () -> Boolean) {
        // A real neural encoder now runs for each image, including on slower emulators.
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for analysis" }
            SystemClock.sleep(25)
        }
    }
}
