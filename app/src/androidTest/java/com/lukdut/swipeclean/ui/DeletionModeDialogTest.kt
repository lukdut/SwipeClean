package com.lukdut.swipeclean.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lukdut.swipeclean.data.DeletionMode
import com.lukdut.swipeclean.ui.theme.SwipeCleanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeletionModeDialogTest {
    @get:Rule val compose = createComposeRule()
    private val confirmedModes = mutableListOf<DeletionMode>()
    private var dismissals = 0

    @Test fun defaultSystemTrashOnlyRunsAfterConfirmation() {
        showDialog()
        compose.onNodeWithText(DeletionMode.SYSTEM_TRASH.label).performScrollTo().assertIsSelected()
        compose.onNodeWithText(DeletionMode.PERMANENT.label).performScrollTo().assertIsNotSelected()
        compose.runOnIdle { assertTrue(confirmedModes.isEmpty()) }

        compose.onNodeWithText("В системную корзину").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(DeletionMode.SYSTEM_TRASH), confirmedModes)
            assertEquals(0, dismissals)
        }
    }

    @Test fun choosingPermanentDoesNotDeleteUntilExplicitConfirmation() {
        showDialog()
        compose.onNodeWithText(DeletionMode.PERMANENT.label).performScrollTo().performClick()
            .assertIsSelected()
        compose.runOnIdle { assertTrue(confirmedModes.isEmpty()); assertEquals(0, dismissals) }

        compose.onNodeWithText("Удалить навсегда").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(DeletionMode.PERMANENT), confirmedModes) }
    }

    @Test fun cancellingAfterChangingSelectionDoesNotConfirmAMode() {
        showDialog()
        compose.onNodeWithText(DeletionMode.PERMANENT.label).performScrollTo().performClick()
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle {
            assertTrue(confirmedModes.isEmpty())
            assertEquals(1, dismissals)
        }
    }

    @Test fun unsupportedSystemTrashCannotProceedWithoutExplicitPermanentSelection() {
        showDialog(systemTrashSupported = false)
        compose.onNodeWithText(DeletionMode.SYSTEM_TRASH.label).performScrollTo()
            .assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithText("В системную корзину").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(confirmedModes.isEmpty()) }

        compose.onNodeWithText(DeletionMode.PERMANENT.label).performScrollTo().performClick()
        compose.onNodeWithText("Удалить навсегда").assertIsEnabled()
        compose.runOnIdle { assertTrue(confirmedModes.isEmpty()) }
        compose.onNodeWithText("Удалить навсегда").performClick()
        compose.runOnIdle { assertEquals(listOf(DeletionMode.PERMANENT), confirmedModes) }
    }

    private fun showDialog(systemTrashSupported: Boolean = true) {
        compose.setContent {
            SwipeCleanTheme {
                DeletionModeDialog(
                    initialMode = DeletionMode.SYSTEM_TRASH,
                    onConfirm = { confirmedModes += it },
                    onDismiss = { dismissals++ },
                    systemTrashSupported = systemTrashSupported
                )
            }
        }
    }
}
