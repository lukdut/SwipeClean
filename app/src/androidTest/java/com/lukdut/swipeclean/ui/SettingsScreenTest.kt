package com.lukdut.swipeclean.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lukdut.swipeclean.data.PhotoSettings
import com.lukdut.swipeclean.data.TestModelFixture
import com.lukdut.swipeclean.data.model.ModelState
import com.lukdut.swipeclean.data.model.InstalledModel
import com.lukdut.swipeclean.data.model.ModelDownloadProgress
import com.lukdut.swipeclean.data.model.ModelDownloadStage
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.io.File
import com.lukdut.swipeclean.analysis.AnalysisProgress
import com.lukdut.swipeclean.data.SortOrder
import com.lukdut.swipeclean.ui.theme.SwipeCleanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val installedModel = ModelState(initializing = false,
        installed = InstalledModel(TestModelFixture.spec(context), File(context.cacheDir, "ui-model.onnx")))
    private var starts = 0
    private var pauses = 0
    private var resets = 0
    private var forgotten = 0

    @Test
    fun personalPriorityAndHistoryResetDoNotStartAnalysis() {
        showSettings(AnalysisProgress(total = 100))
        compose.onNodeWithContentDescription("Похожие фотографии: Выкл.").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Похожие фотографии: Выкл.").assertIsSelected()
        compose.onNodeWithText("Очистить историю рекомендаций").performScrollTo().performClick()
        compose.onNodeWithText("Очистить историю рекомендаций?").assertExists()
        compose.runOnIdle { assertEquals(0, forgotten) }
        compose.onNodeWithText("Очистить").performClick()
        compose.runOnIdle { assertEquals(1, forgotten); assertEquals(0, starts) }
    }

    @Test fun firstAnalysisExplainsDownloadAndRequiresExplicitTap() {
        showSettings(AnalysisProgress(total = 10), model = ModelState(initializing = false,
            bundled = TestModelFixture.spec(context)))
        compose.onNodeWithText("Скачать модель и анализировать").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithText("Скачать модель и анализировать").performClick()
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test fun interruptedDownloadOffersRetryWithoutStartingAutomatically() {
        showSettings(AnalysisProgress(total = 10, error = "Нет сети"), model = ModelState(initializing = false,
            bundled = TestModelFixture.spec(context)))
        compose.onNodeWithText("Повторить загрузку модели").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithText("Повторить загрузку модели").performClick()
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test fun downloadingShowsByteProgressAndCanBeCancelled() {
        val download = ModelDownloadProgress(ModelDownloadStage.DOWNLOADING, 10 * 1_048_576, 90 * 1_048_576)
        showSettings(AnalysisProgress(total = 10, running = true, preparing = true, modelDownload = download))
        compose.onNodeWithText(download.label).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Приостановить").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, pauses); assertEquals(0, starts) }
    }

    @Test
    fun selectingSmartSortAndPrioritiesDoesNotStartAnalysis() {
        showSettings(AnalysisProgress(total = 100))
        compose.onNodeWithText(SortOrder.ByPotentiallyUnwanted.label).performClick()
        compose.onNodeWithContentDescription("Размытие: Высокий").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Размытие: Высокий").assertIsSelected()
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithText("Анализировать фото").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test
    fun runningAnalysisCanBePausedAndRequiresExplicitResume() {
        showSettings(AnalysisProgress(total = 100, analyzed = 20, running = true))
        compose.onNodeWithText("Приостановить").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, pauses)
            assertEquals(0, starts)
        }
        compose.onNodeWithText("Продолжить анализ").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test
    fun completedAnalysisCannotBeStartedAgain() {
        showSettings(AnalysisProgress(total = 10, analyzed = 10))
        compose.onNodeWithText("Анализировать фото").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun skippedPhotosCompleteAnalysisAndCanBeRetriedExplicitly() {
        showSettings(AnalysisProgress(total = 10, analyzed = 8, skipped = 2))
        compose.onNodeWithText("Анализ завершён").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Не удалось прочитать: 2. Их можно проверить повторно.").assertExists()
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithText("Повторить для пропущенных").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test
    fun analysisCompletesEvenWhenEveryPhotoIsSkipped() {
        showSettings(AnalysisProgress(total = 10, skipped = 10))
        compose.onNodeWithText("Анализ завершён").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, starts) }
    }

    @Test
    fun resetOnlyRunsAfterConfirmation() {
        showSettings(AnalysisProgress(total = 10))
        compose.onNodeWithText("Сбросить прогресс").performScrollTo().performClick()
        compose.onNodeWithText("Сбросить прогресс просмотра?").assertExists()
        compose.runOnIdle { assertEquals(0, resets) }
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { assertEquals(0, resets) }
        compose.onNodeWithText("Сбросить прогресс").performClick()
        compose.onNodeWithText("Сбросить").performClick()
        compose.runOnIdle { assertEquals(1, resets) }
        compose.onNodeWithText("Прогресс сброшен. Можно начать просмотр заново.").assertExists()
    }

    @Test
    fun analysisShortcutScrollsToTheButtonWithoutStartingAnalysis() {
        showSettings(AnalysisProgress(total = 100), openAtAnalysis = true, compact = true)
        compose.onNodeWithText("Анализировать фото").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithText("Анализировать фото").performClick()
        compose.runOnIdle { assertEquals(1, starts) }
    }

    private fun showSettings(progress: AnalysisProgress, openAtAnalysis: Boolean = false, compact: Boolean = false, model: ModelState = installedModel) {
        compose.setContent {
            var settings by remember { mutableStateOf(PhotoSettings()) }
            var analysis by remember { mutableStateOf(progress) }
            var reset by remember { mutableStateOf(ProgressResetState()) }
            SwipeCleanTheme {
                Box(if (compact) Modifier.height(400.dp) else Modifier.fillMaxSize()) {
                    SettingsScreen(
                        modelState = model,
                        settings = settings, analysis = analysis, progressReset = reset, isLoading = false,
                        hasAnalysisResults = progress.analyzed > 0,
                        openAtAnalysis = openAtAnalysis,
                        onSortOrder = { settings = settings.copy(sortOrder = it) },
                        onPriority = { signal, priority -> settings = settings.withPriority(signal, priority) },
                        onPersonalization = { settings = settings.copy(personalization = it) },
                        onForgetFeedback = { forgotten++ },
                        onStartAnalysis = { starts++ },
                        onPauseAnalysis = { pauses++; analysis = analysis.copy(running = false) },
                        onResetProgress = { resets++; reset = ProgressResetState(completed = true) },
                        onBack = {}
                    )
                }
            }
        }
    }
}
