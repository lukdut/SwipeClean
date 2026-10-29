package com.lukdut.swipeclean.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lukdut.swipeclean.data.SortOrder
import com.lukdut.swipeclean.ui.theme.SwipeCleanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SortMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun selectingEitherDirectionUpdatesTheHeaderAndKeepsTheSelectionWhenReopened() {
        val selections = mutableListOf<SortOrder>()
        compose.setContent {
            var selected by remember { mutableStateOf(SortOrder.ByDateDesc) }
            SwipeCleanTheme {
                SortMenu(
                    current = selected,
                    hasAnalysisResults = false,
                    onSelect = { selected = it; selections += it },
                    onOpenAnalysis = { error("Date and size sorting must not require analysis") }
                )
            }
        }

        val orders = listOf(SortOrder.ByDateAsc, SortOrder.BySizeAsc,
            SortOrder.ByDateDesc, SortOrder.BySizeDesc)
        var current = SortOrder.ByDateDesc
        for (order in orders) {
            compose.onNodeWithContentDescription("Сортировка").performClick()
            compose.onNodeWithText("По дате").assertIsDisplayed()
            compose.onNodeWithText("По размеру").assertIsDisplayed()
            compose.onNode(hasText(current.label) and
                SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertIsSelected()
            compose.onNode(hasText(order.label) and
                SemanticsMatcher.expectValue(SemanticsProperties.Selected, false)).performClick()

            compose.onNodeWithText("По дате").assertDoesNotExist()
            compose.onNodeWithText("По размеру").assertDoesNotExist()
            compose.onNodeWithText(order.label).assertIsDisplayed()
            compose.onNodeWithContentDescription("Сортировка").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, order.label))
            current = order
        }

        compose.onNodeWithContentDescription("Сортировка").performClick()
        compose.onNode(hasText(current.label) and
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertIsSelected()
        compose.runOnIdle { assertEquals(orders, selections) }
    }

    @Test
    fun unavailableSortOpensAnalysisAndBecomesSelectableAfterResultsArrive() {
        val hasResults = mutableStateOf(false)
        var analysisRequests = 0
        val selections = mutableListOf<SortOrder>()
        compose.setContent {
            var selected by remember { mutableStateOf(SortOrder.ByDateDesc) }
            SwipeCleanTheme {
                SortMenu(
                    current = selected,
                    hasAnalysisResults = hasResults.value,
                    onSelect = { selected = it; selections += it },
                    onOpenAnalysis = { analysisRequests++ }
                )
            }
        }

        compose.onNodeWithContentDescription("Сортировка").performClick()
        compose.onNodeWithText("Неудачные").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, analysisRequests)
            assertEquals(emptyList<SortOrder>(), selections)
        }
        compose.onNodeWithText(SortOrder.ByDateDesc.label).assertIsDisplayed()

        compose.runOnIdle { hasResults.value = true }
        compose.onNodeWithContentDescription("Сортировка").performClick()
        compose.onNodeWithText("Неудачные").performClick()
        compose.runOnIdle {
            assertEquals(listOf(SortOrder.ByPotentiallyUnwanted), selections)
            assertEquals(1, analysisRequests)
        }
        compose.onNodeWithText("Неудачные").assertIsDisplayed()
    }
}
