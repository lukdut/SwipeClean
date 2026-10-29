package com.lukdut.swipeclean.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lukdut.swipeclean.data.DeletionMode

@Composable
internal fun DeletionModeOptions(
    selected: DeletionMode,
    onSelect: (DeletionMode) -> Unit,
    systemTrashSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
) {
    Column(Modifier.selectableGroup()) {
        DeletionMode.entries.forEach { mode ->
            val enabled = mode != DeletionMode.SYSTEM_TRASH || systemTrashSupported
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected == mode,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(mode) }
                    )
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RadioButton(selected = selected == mode, onClick = null, enabled = enabled)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(mode.label, style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (enabled) mode.description
                        else "Недоступна на этой версии Android. Для удаления выберите «Удалять навсегда».",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
