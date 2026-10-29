package com.lukdut.swipeclean.data

import android.content.SharedPreferences
import androidx.core.content.edit

class SettingsRepository(private val preferences: SharedPreferences) {
    fun load(): PhotoSettings {
        val savedDeletionMode = DeletionMode.entries.firstOrNull {
            it.name == preferences.getString("deletion_mode", null)
        }
        var settings = PhotoSettings(
            sortOrder = SortOrder.entries.firstOrNull {
                it.name == preferences.getString("sort_order", null)
            } ?: SortOrder.default,
            deletionMode = savedDeletionMode ?: DeletionMode.SYSTEM_TRASH,
            deletionModeConfirmed = savedDeletionMode != null &&
                preferences.getBoolean("deletion_mode_confirmed", false)
        )
        for (signal in QualitySignal.entries) {
            val priority = Priority.entries.firstOrNull {
                it.name == preferences.getString(signal.name, null)
            } ?: Priority.NORMAL
            settings = settings.withPriority(signal, priority)
        }
        return settings.copy(personalization = Priority.entries.firstOrNull {
            it.name == preferences.getString("personalization", null)
        } ?: Priority.NORMAL)
    }

    fun save(settings: PhotoSettings) {
        preferences.edit {
            putString("sort_order", settings.sortOrder.name)
            putString("personalization", settings.personalization.name)
            putString("deletion_mode", settings.deletionMode.name)
            putBoolean("deletion_mode_confirmed", settings.deletionModeConfirmed)
            QualitySignal.entries.forEach { putString(it.name, settings.priority(it).name) }
        }
    }
}
