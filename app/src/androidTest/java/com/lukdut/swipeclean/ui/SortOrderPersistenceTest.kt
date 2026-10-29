package com.lukdut.swipeclean.ui

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.lukdut.swipeclean.data.MediaStoreRepository
import com.lukdut.swipeclean.data.PhotoQuality
import com.lukdut.swipeclean.data.PhotoSettings
import com.lukdut.swipeclean.data.Priority
import com.lukdut.swipeclean.data.SettingsRepository
import com.lukdut.swipeclean.data.SortOrder
import com.lukdut.swipeclean.data.db.AppDatabase
import com.lukdut.swipeclean.data.db.PhotoAnalysisEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class SortOrderPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as Application
    private val preferences = application.getSharedPreferences("photo_settings", Context.MODE_PRIVATE)
    private val settingsRepository = SettingsRepository(preferences)
    private val analysisDao = AppDatabase.getInstance(application).photoAnalysisDao()
    private var originalPreferences = emptyMap<String, Any?>()
    private var originalAnalysis = emptyList<PhotoAnalysisEntity>()
    private val photoUris = mutableListOf<Uri>()
    private var fixtureAnalysis = emptyList<PhotoAnalysisEntity>()
    private var store: ViewModelStore? = null

    @Before
    fun setUp() {
        originalPreferences = preferences.all.toMap()
        originalAnalysis = runBlocking { analysisDao.getAll() }
        instrumentation.uiAutomation.grantRuntimePermission(application.packageName,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE)
        settingsRepository.save(PhotoSettings(personalization = Priority.OFF))
        val resolver = application.contentResolver
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            repeat(2) { index ->
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "sort-persistence-${System.nanoTime()}-$index.png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SwipeCleanSortPersistenceTest")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }))
                photoUris += uri
                checkNotNull(resolver.openOutputStream(uri)).use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
        } finally {
            bitmap.recycle()
        }
        runBlocking {
            val ids = photoUris.map { ContentUris.parseId(it) }
            val photos = MediaStoreRepository(application).loadAllPhotos().associateBy { it.id }
            // The older fixture has a higher unwanted score, reversing the default date/id order.
            fixtureAnalysis = ids.mapIndexed { index, id ->
                PhotoAnalysisEntity.from(photos.getValue(id),
                    PhotoQuality(if (index == 0) 1f else 0f, 0f, 0f, 0f))
            }
            analysisDao.deleteByIds(originalAnalysis.map { it.mediaStoreId })
            analysisDao.upsertAll(fixtureAnalysis)
        }
    }

    @After
    fun tearDown() {
        closeViewModel()
        runBlocking {
            analysisDao.deleteByIds(analysisDao.getAll().map { it.mediaStoreId })
            analysisDao.upsertAll(originalAnalysis)
        }
        photoUris.forEach { application.contentResolver.delete(it, null, null) }
        preferences.edit().apply {
            clear()
            originalPreferences.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }.commit()
    }

    @Test
    fun everySortOrderSurvivesANewViewModelAndPhotoReload() {
        for (order in SortOrder.entries) {
            val previous = openViewModel()
            await { previous.hasAnalysisResults.value }
            instrumentation.runOnMainSync { previous.setSortOrder(order) }
            assertSortOrder(order, previous)
            closeViewModel()

            val reopened = openViewModel()
            assertNotSame(previous, reopened)
            assertSortOrder(order, reopened)
            closeViewModel()
        }
    }

    @Test
    fun savedSmartSortSurvivesMissingAnalysisAndResumesWhenResultsReturn() {
        val previous = openViewModel()
        await { previous.hasAnalysisResults.value }
        instrumentation.runOnMainSync { previous.setSortOrder(SortOrder.ByPotentiallyUnwanted) }
        assertSortOrder(SortOrder.ByPotentiallyUnwanted, previous)
        closeViewModel()
        runBlocking { analysisDao.deleteByIds(fixtureAnalysis.map { it.mediaStoreId }) }

        val reopened = openViewModel()
        assertNotSame(previous, reopened)
        assertFalse(reopened.hasAnalysisResults.value)
        assertSortOrder(SortOrder.ByPotentiallyUnwanted, reopened)

        runBlocking { analysisDao.upsertAll(fixtureAnalysis) }
        await {
            reopened.hasAnalysisResults.value &&
                reopened.swipeUiState.value.currentPhoto?.id == fixtureAnalysis.first().mediaStoreId
        }
        assertSortOrder(SortOrder.ByPotentiallyUnwanted, reopened)
    }

    private fun openViewModel(): AppViewModel {
        check(store == null)
        lateinit var viewModel: AppViewModel
        instrumentation.runOnMainSync {
            val newStore = ViewModelStore()
            store = newStore
            viewModel = ViewModelProvider(newStore,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application))[AppViewModel::class.java]
            // No Activity is visible, so these tests cannot trigger automatic analysis or downloads.
            viewModel.loadPhotos()
        }
        await { !viewModel.isLoading.value && !viewModel.modelState.value.initializing }
        assertNull(viewModel.loadError.value)
        return viewModel
    }

    private fun closeViewModel() {
        instrumentation.runOnMainSync {
            store?.clear()
            store = null
        }
    }

    private fun assertSortOrder(expected: SortOrder, viewModel: AppViewModel) {
        assertEquals(expected, viewModel.settings.value.sortOrder)
        assertEquals(expected, viewModel.swipeUiState.value.sortOrder)
        assertEquals(expected, settingsRepository.load().sortOrder)
        assertEquals(expected.name, preferences.getString("sort_order", null))
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for restored sort order" }
            SystemClock.sleep(20)
        }
    }
}
