package com.lukdut.swipeclean.ui

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.lukdut.swipeclean.MainActivity
import com.lukdut.swipeclean.analysis.AnalysisCoordinator
import com.lukdut.swipeclean.data.AnalysisHistoryRepository
import com.lukdut.swipeclean.data.MediaStoreRepository
import com.lukdut.swipeclean.data.PhotoQuality
import com.lukdut.swipeclean.data.TestModelFixture
import com.lukdut.swipeclean.data.db.AppDatabase
import com.lukdut.swipeclean.data.db.PhotoAnalysisEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class AutomaticAnalysisTest {
    @Test
    fun firstAndPartialAnalysisStillRequireManualStart() = withFixture { fixture ->
        val first = fixture.addPhoto()
        val second = fixture.addPhoto()
        fixture.cacheGallery(except = setOf(first, second))
        fixture.history.markStarted()
        fixture.open()
        fixture.awaitLoaded(missing = 2)
        assertRemainsStopped(fixture.viewModel)

        // One existing result must not be mistaken for a previously completed gallery.
        fixture.cacheGallery(except = setOf(second))
        fixture.scenario.onActivity { fixture.viewModel.loadPhotos() }
        fixture.awaitLoaded(missing = 1)
        assertRemainsStopped(fixture.viewModel)
        assertFalse(fixture.history.hasCompletedGalleryAnalysis(fixture.model.spec))
    }

    @Test
    fun newPhotosAreAnalyzedOnReturnAndReopenAfterCompleteGallery() = withFixture { fixture ->
        fixture.addPhoto()
        fixture.cacheGallery()
        fixture.history.markStarted()
        fixture.open()
        fixture.awaitLoaded(missing = 0)
        await { fixture.history.hasCompletedGalleryAnalysis(fixture.model.spec) }
        assertRemainsStopped(fixture.viewModel)

        fixture.scenario.moveToState(Lifecycle.State.CREATED)
        val newPhoto = fixture.addPhoto()
        fixture.scenario.moveToState(Lifecycle.State.RESUMED)
        fixture.awaitAnalyzed(newPhoto)
        assertTrue(fixture.history.hasCompletedGalleryAnalysis(fixture.model.spec))

        // A new ViewModel must use the saved completion state, without an explicit start call.
        fixture.scenario.close()
        val afterClose = fixture.addPhoto()
        fixture.open()
        fixture.awaitAnalyzed(afterClose)
        assertTrue(fixture.history.hasCompletedGalleryAnalysis(fixture.model.spec))
    }

    @Test
    fun oneHundredNewPhotosStayManualButNinetyNineStartAndRespectPause() = withFixture { fixture ->
        fixture.addPhoto()
        fixture.cacheGallery()
        fixture.history.markCompleted(fixture.model.spec)
        val newPhotos = List(100) { fixture.addPhoto() }
        fixture.open()
        fixture.awaitLoaded(missing = 100)
        assertRemainsStopped(fixture.viewModel)

        fixture.scenario.moveToState(Lifecycle.State.CREATED)
        fixture.deletePhoto(newPhotos.last())
        fixture.scenario.moveToState(Lifecycle.State.RESUMED)
        await {
            val progress = fixture.viewModel.analysisProgress.value
            assertNull(progress.error)
            progress.running && !progress.preparing
        }
        fixture.scenario.onActivity { fixture.viewModel.pauseAnalysis() }
        await { !fixture.viewModel.analysisProgress.value.running }
        assertTrue(fixture.viewModel.analysisProgress.value.remaining > 0)
        assertFalse(fixture.history.hasCompletedGalleryAnalysis(fixture.model.spec))

        fixture.scenario.moveToState(Lifecycle.State.CREATED)
        fixture.scenario.moveToState(Lifecycle.State.RESUMED)
        assertRemainsStopped(fixture.viewModel)
    }

    @Test
    fun photosDiscoveredDuringAnAutomaticPassAreAnalyzedAfterItFinishes() = withFixture { fixture ->
        fixture.addPhoto()
        fixture.cacheGallery()
        fixture.history.markCompleted(fixture.model.spec)
        repeat(20) { fixture.addPhoto() }
        fixture.open()
        await {
            val progress = fixture.viewModel.analysisProgress.value
            assertNull(progress.error)
            progress.running && !progress.preparing
        }
        val initialTotal = fixture.viewModel.analysisProgress.value.total
        val initialQueueSize = fixture.viewModel.swipeUiState.value.progress.second

        // The service has already captured its input; the refreshed ViewModel sees one more photo.
        fixture.scenario.moveToState(Lifecycle.State.CREATED)
        val addedDuringPass = fixture.addPhoto()
        fixture.scenario.moveToState(Lifecycle.State.RESUMED)
        await {
            !fixture.viewModel.isLoading.value &&
                fixture.viewModel.swipeUiState.value.progress.second == initialQueueSize + 1
        }
        assertTrue("The original pass should still be running when the new photo is discovered",
            fixture.viewModel.analysisProgress.value.running)
        assertEquals(initialTotal, fixture.viewModel.analysisProgress.value.total)

        fixture.awaitAnalyzed(addedDuringPass)
        assertEquals(initialTotal + 1, fixture.viewModel.analysisProgress.value.analyzed)
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        Fixture().use(test)
    }

    private class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val context = instrumentation.targetContext
        private val resolver = context.contentResolver
        private val dao = AppDatabase.getInstance(context).photoAnalysisDao()
        private val preferences = context.getSharedPreferences(
            AnalysisHistoryRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
        private val previousPreferences = preferences.all.toMap()
        private val previousRows = runBlocking { dao.getAll() }
        private val touchedIds = mutableSetOf<Long>()
        private val uris = mutableMapOf<Long, Uri>()
        private val bytes = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).let { bitmap ->
            try {
                ByteArrayOutputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    it.toByteArray()
                }
            } finally { bitmap.recycle() }
        }
        val history = AnalysisHistoryRepository(preferences)
        val model = runBlocking { TestModelFixture.install(context) }
        lateinit var scenario: ActivityScenario<MainActivity>
        lateinit var viewModel: AppViewModel

        init {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName,
                if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
                else Manifest.permission.READ_EXTERNAL_STORAGE)
            // Deliberately do not grant POST_NOTIFICATIONS or invoke the manual start UI.
            runBlocking { withContext(Dispatchers.Main) {
                AnalysisCoordinator.pauseAndJoin()
                AnalysisCoordinator.clearFinishedProgress()
            } }
        }

        fun addPhoto(): Long {
            val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "automatic-analysis-${System.nanoTime()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SwipeCleanAutomaticAnalysisTest")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }))
            val id = ContentUris.parseId(uri)
            uris[id] = uri
            touchedIds += id
            checkNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return id
        }

        fun deletePhoto(id: Long) {
            uris.remove(id)?.let { resolver.delete(it, null, null) }
        }

        fun cacheGallery(except: Set<Long> = emptySet()) = runBlocking {
            val photos = MediaStoreRepository(context).loadAllPhotos().filter { it.id !in except }
            touchedIds += photos.map { it.id }
            dao.upsertAll(photos.map { photo ->
                PhotoAnalysisEntity.from(photo, PhotoQuality(0f, 0f, 0f, 0f)).copy(
                    embedding = ByteArray(model.spec.dimensions).apply { this[0] = 127 },
                    embeddingVersion = model.spec.embeddingVersion)
            })
        }

        fun open() {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { viewModel = ViewModelProvider(it)[AppViewModel::class.java] }
        }

        fun awaitLoaded(missing: Int) = await {
            val progress = viewModel.analysisProgress.value
            !viewModel.isLoading.value && progress.total > 0 && progress.total - progress.analyzed == missing
        }

        fun awaitAnalyzed(id: Long) {
            await {
                assertNull(viewModel.analysisProgress.value.error)
                runBlocking { dao.getById(id)?.currentEmbedding(model.spec) != null } &&
                    !viewModel.analysisProgress.value.running &&
                    history.hasCompletedGalleryAnalysis(model.spec)
            }
            assertEquals(0, viewModel.analysisProgress.value.skipped)
        }

        override fun close() {
            if (::scenario.isInitialized) scenario.close()
            runBlocking {
                withContext(Dispatchers.Main) {
                    AnalysisCoordinator.pauseAndJoin()
                    AnalysisCoordinator.clearFinishedProgress()
                }
                dao.deleteByIds(touchedIds)
                dao.upsertAll(previousRows.filter { it.mediaStoreId in touchedIds })
            }
            uris.values.forEach { resolver.delete(it, null, null) }
            preferences.edit().apply {
                clear()
                previousPreferences.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                    }
                }
            }.commit()
        }
    }

    companion object {
        private fun assertRemainsStopped(viewModel: AppViewModel) {
            val deadline = SystemClock.elapsedRealtime() + 500
            while (SystemClock.elapsedRealtime() < deadline) {
                assertFalse(viewModel.analysisProgress.value.running)
                SystemClock.sleep(25)
            }
        }

        private fun await(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 120_000
            while (!condition()) {
                check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for automatic analysis" }
                SystemClock.sleep(25)
            }
        }
    }
}
