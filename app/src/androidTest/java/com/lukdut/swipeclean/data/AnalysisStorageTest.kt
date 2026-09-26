package com.lukdut.swipeclean.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lukdut.swipeclean.data.db.AppDatabase
import com.lukdut.swipeclean.data.db.PhotoAnalysisEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisStorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun settingsSurviveRepositoryRecreation() {
        val preferences = context.getSharedPreferences("settings_test", Context.MODE_PRIVATE)
        try {
            val settings = PhotoSettings(SortOrder.ByPotentiallyUnwanted, Priority.HIGH,
                Priority.OFF, Priority.NORMAL, Priority.HIGH)
            SettingsRepository(preferences).save(settings)
            assertEquals(settings, SettingsRepository(preferences).load())
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun cachedQualityRoundTripsAndRejectsChangedPhotosOrAnalyzer() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val photo = MediaPhoto(1L, Uri.parse("content://media/external/images/media/1"), "test", 100, 1234, 200)
            val entity = PhotoAnalysisEntity.from(photo, PhotoQuality(0.6f, 0f, 0.2f, 0f))
            db.photoAnalysisDao().upsert(entity)
            val cached = db.photoAnalysisDao().getAll().single()
            assertEquals(entity, cached)
            assertTrue(cached.matches(photo))
            assertFalse(cached.matches(photo.copy(dateModified = 201)))
            assertFalse(cached.matches(photo.copy(size = 4321)))
            assertFalse(cached.matches(photo.copy(uri = Uri.EMPTY)))
            assertFalse(cached.copy(analyzerVersion = 0).matches(photo))
        } finally {
            db.close()
        }
    }
}
