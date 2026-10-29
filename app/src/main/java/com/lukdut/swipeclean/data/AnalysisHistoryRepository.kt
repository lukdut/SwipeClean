package com.lukdut.swipeclean.data

import android.content.SharedPreferences
import androidx.core.content.edit
import com.lukdut.swipeclean.data.model.ModelSpec

/** Remembers a fully analyzed gallery across app restarts, for the current analysis contract. */
class AnalysisHistoryRepository(private val preferences: SharedPreferences) {
    fun hasCompletedGalleryAnalysis(model: ModelSpec): Boolean =
        preferences.getString(COMPLETED_EMBEDDING_VERSION, null) == model.embeddingVersion &&
            preferences.getInt(COMPLETED_ANALYZER_VERSION, -1) == PhotoQualityCalculator.VERSION

    fun canStartAutomatically(model: ModelSpec, missingCount: Int): Boolean =
        missingCount in 1 until AUTOMATIC_ANALYSIS_LIMIT && hasCompletedGalleryAnalysis(model)

    /** A paused or failed pass must be resumed explicitly, even if fewer than 100 photos remain. */
    fun markStarted() {
        preferences.edit {
            remove(COMPLETED_EMBEDDING_VERSION)
            remove(COMPLETED_ANALYZER_VERSION)
        }
    }

    fun markCompleted(model: ModelSpec) {
        preferences.edit {
            putString(COMPLETED_EMBEDDING_VERSION, model.embeddingVersion)
            putInt(COMPLETED_ANALYZER_VERSION, PhotoQualityCalculator.VERSION)
        }
    }

    companion object {
        const val PREFERENCES_NAME = "analysis_history"
        private const val COMPLETED_EMBEDDING_VERSION = "completed_embedding_version"
        private const val COMPLETED_ANALYZER_VERSION = "completed_analyzer_version"
        const val AUTOMATIC_ANALYSIS_LIMIT = 100
    }
}
