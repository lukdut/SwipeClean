package com.lukdut.swipeclean.data

import android.content.SharedPreferences
import androidx.core.content.edit

class SettingsRepository(private val preferences: SharedPreferences) {
    fun load(): PhotoSettings {
        var settings = PhotoSettings(
            sortOrder = SortOrder.entries.firstOrNull {
                it.name == preferences.getString("sort_order", null)
            } ?: SortOrder.default
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
            QualitySignal.entries.forEach { putString(it.name, settings.priority(it).name) }
        }
    }
}
