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
    private var starts = 0
    private var pauses = 0
    private var resets = 0

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

    private fun showSettings(progress: AnalysisProgress, openAtAnalysis: Boolean = false, compact: Boolean = false) {
        compose.setContent {
            var settings by remember { mutableStateOf(PhotoSettings()) }
            var analysis by remember { mutableStateOf(progress) }
            var reset by remember { mutableStateOf(ProgressResetState()) }
            SwipeCleanTheme {
                Box(if (compact) Modifier.height(400.dp) else Modifier.fillMaxSize()) {
                    SettingsScreen(
                        settings = settings, analysis = analysis, progressReset = reset, isLoading = false,
                        hasAnalysisResults = progress.analyzed > 0,
                        openAtAnalysis = openAtAnalysis,
                        onSortOrder = { settings = settings.copy(sortOrder = it) },
                        onPriority = { signal, priority -> settings = settings.withPriority(signal, priority) },
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
