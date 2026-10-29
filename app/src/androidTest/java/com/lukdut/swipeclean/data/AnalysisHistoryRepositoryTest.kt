package com.lukdut.swipeclean.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lukdut.swipeclean.data.model.ModelSpec
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisHistoryRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("analysis_history_test", Context.MODE_PRIVATE)
    private val repository = AnalysisHistoryRepository(preferences)
    private val model = ModelSpec("test-v1", "Test", "https://example.com/model.onnx", "a".repeat(64),
        100, "pixels", "features", 224, 768, List(3) { 0.5f }, List(3) { 0.5f })

    @Before
    fun clearBeforeTest() {
        preferences.edit().clear().commit()
    }

    @After
    fun clearAfterTest() {
        preferences.edit().clear().commit()
    }

    @Test
    fun completionSurvivesRepositoryRecreation() {
        assertFalse(repository.hasCompletedGalleryAnalysis(model))
        assertFalse(repository.canStartAutomatically(model, 1))

        repository.markCompleted(model)

        val reopened = AnalysisHistoryRepository(
            context.getSharedPreferences("analysis_history_test", Context.MODE_PRIVATE))
        assertTrue(reopened.hasCompletedGalleryAnalysis(model))
        assertTrue(reopened.canStartAutomatically(model, 1))
    }

    @Test
    fun automaticAnalysisRequiresBetweenOneAndNinetyNineMissingPhotos() {
        repository.markCompleted(model)

        assertFalse(repository.canStartAutomatically(model, 0))
        assertTrue(repository.canStartAutomatically(model, 1))
        assertTrue(repository.canStartAutomatically(model, 99))
        assertFalse(repository.canStartAutomatically(model, 100))
        assertFalse(repository.canStartAutomatically(model, 101))
    }

    @Test
    fun changedModelCannotUsePreviousCompletion() {
        repository.markCompleted(model)
        val updatedModel = model.copy(sha256 = "b".repeat(64))

        assertFalse(repository.hasCompletedGalleryAnalysis(updatedModel))
        assertFalse(repository.canStartAutomatically(updatedModel, 1))

        repository.markCompleted(updatedModel)
        assertTrue(repository.canStartAutomatically(updatedModel, 1))
        assertFalse(repository.hasCompletedGalleryAnalysis(model))
    }

    @Test
    fun changedAnalyzerCannotUsePreviousCompletion() {
        repository.markCompleted(model)
        preferences.edit().putInt("completed_analyzer_version", PhotoQualityCalculator.VERSION - 1).commit()

        assertFalse(repository.hasCompletedGalleryAnalysis(model))
        assertFalse(repository.canStartAutomatically(model, 1))
    }

    @Test
    fun startingPassSuppressesAutomaticRetriesUntilFullCompletion() {
        repository.markCompleted(model)
        repository.markStarted()

        val reopened = AnalysisHistoryRepository(preferences)
        assertFalse(reopened.hasCompletedGalleryAnalysis(model))
        assertFalse(reopened.canStartAutomatically(model, 1))

        reopened.markCompleted(model)
        assertTrue(repository.canStartAutomatically(model, 1))
    }
}
