package com.lukdut.swipeclean.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeletionModeSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("deletion_mode_settings_test", Context.MODE_PRIVATE)
    private val repository = SettingsRepository(preferences)

    @Before fun setUp() { preferences.edit().clear().commit() }
    @After fun tearDown() { preferences.edit().clear().commit() }

    @Test fun newAndExistingInstallationsDefaultToUnconfirmedSystemTrash() {
        assertEquals(DeletionMode.SYSTEM_TRASH, repository.load().deletionMode)
        assertFalse(repository.load().deletionModeConfirmed)

        // Preferences written by older versions have no deletion mode yet.
        preferences.edit().putString("sort_order", SortOrder.BySizeDesc.name)
            .putString("personalization", Priority.OFF.name).commit()
        val migrated = repository.load()
        assertEquals(DeletionMode.SYSTEM_TRASH, migrated.deletionMode)
        assertFalse(migrated.deletionModeConfirmed)
        assertEquals(SortOrder.BySizeDesc, migrated.sortOrder)
        assertEquals(Priority.OFF, migrated.personalization)
    }

    @Test fun selectedModeAndConfirmationSurviveReloadAndOtherSettingsChanges() {
        for (mode in DeletionMode.entries) {
            repository.save(PhotoSettings(deletionMode = mode, deletionModeConfirmed = true))
            val reloaded = SettingsRepository(preferences).load()
            assertEquals(mode, reloaded.deletionMode)
            assertTrue(reloaded.deletionModeConfirmed)

            repository.save(reloaded.copy(sortOrder = SortOrder.ByDateAsc, blur = Priority.HIGH))
            assertEquals(mode, repository.load().deletionMode)
            assertTrue(repository.load().deletionModeConfirmed)
        }
    }

    @Test fun savingOtherSettingsDoesNotConfirmTheDefaultMode() {
        repository.save(repository.load().copy(sortOrder = SortOrder.BySizeDesc))
        assertEquals(DeletionMode.SYSTEM_TRASH, repository.load().deletionMode)
        assertFalse(repository.load().deletionModeConfirmed)
    }

    @Test fun unrecognizedSavedModeCannotKeepAnOldConfirmation() {
        preferences.edit().putString("deletion_mode", "unknown_mode")
            .putBoolean("deletion_mode_confirmed", true).commit()
        assertEquals(DeletionMode.SYSTEM_TRASH, repository.load().deletionMode)
        assertFalse(repository.load().deletionModeConfirmed)
    }
}
