package com.lukdut.swipeclean.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lukdut.swipeclean.data.MediaPhoto
import com.lukdut.swipeclean.data.DeletionMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit
) {
    val markedPhotos by viewModel.markedPhotos.collectAsState()
    val preparation by viewModel.deletePreparation.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val showModePrompt by viewModel.deletionModePrompt.collectAsState()
    val pendingRequest by viewModel.pendingDeleteSender.collectAsState()
    val actionsEnabled = !preparation.running && pendingRequest == null
    val systemTrashUnavailable = settings.deletionMode == DeletionMode.SYSTEM_TRASH &&
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R
    val contentResolver = LocalContext.current.contentResolver
    var viewerPhoto by remember { mutableStateOf<com.lukdut.swipeclean.data.MediaPhoto?>(null) }

    if (showModePrompt) {
        DeletionModeDialog(
            initialMode = settings.deletionMode,
            onConfirm = { viewModel.confirmDeletionMode(it, contentResolver) },
            onDismiss = viewModel::dismissDeletionModePrompt
        )
    }

    viewerPhoto?.let { photo ->
        PhotoViewerDialog(
            photo = photo,
            onDismiss = { viewerPhoto = null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Корзина (${markedPhotos.size})",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (markedPhotos.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp)) {
                        Text(
                            text = when {
                                systemTrashUnavailable -> "На этой версии Android системная корзина недоступна. Выберите способ удаления."
                                settings.deletionMode == DeletionMode.SYSTEM_TRASH ->
                                    "Фото будут перемещены в системную корзину. Место освободится после окончательного удаления."
                                else -> "Фото будут удалены навсегда, без возможности восстановления через корзину."
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (preparation.running) {
                            Text("Подготавливаем фото: ${preparation.completed} из ${preparation.total}",
                                modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
                            LinearProgressIndicator(progress = {
                                preparation.completed.toFloat() / preparation.total.coerceAtLeast(1)
                            }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                            TextButton(onClick = viewModel::cancelDeletePreparation) { Text("Отменить подготовку") }
                        }
                        preparation.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp)) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.restoreAll() },
                                enabled = actionsEnabled,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Восстановить все")
                            }
                            Button(
                                onClick = { viewModel.requestDelete(contentResolver) },
                                enabled = actionsEnabled,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (settings.deletionMode == DeletionMode.SYSTEM_TRASH)
                                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    if (settings.deletionMode == DeletionMode.SYSTEM_TRASH)
                                        Icons.Default.Delete else Icons.Default.DeleteForever,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(when {
                                    systemTrashUnavailable -> "Выбрать режим"
                                    settings.deletionMode == DeletionMode.SYSTEM_TRASH -> "В системную корзину"
                                    else -> "Удалить навсегда"
                                })
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (markedPhotos.isEmpty()) {
            EmptyTrash(modifier = Modifier.padding(padding))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(markedPhotos, key = { it.id }) { photo ->
                    TrashPhotoItem(
                        photo = photo,
                        canRestore = actionsEnabled,
                        onRestore = { viewModel.restorePhoto(photo.id) },
                        onOpen = { viewerPhoto = photo }
                    )
                }
            }
        }
    }
}

@Composable
internal fun DeletionModeDialog(
    initialMode: DeletionMode,
    onConfirm: (DeletionMode) -> Unit,
    onDismiss: () -> Unit,
    systemTrashSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
) {
    var selected by rememberSaveable { mutableStateOf(initialMode) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Как удалять фотографии?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Запомним выбор. Его можно изменить в настройках.")
                DeletionModeOptions(selected, { selected = it }, systemTrashSupported)
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != DeletionMode.SYSTEM_TRASH || systemTrashSupported,
                onClick = { onConfirm(selected) }
            ) {
                Text(if (selected == DeletionMode.SYSTEM_TRASH) "В системную корзину" else "Удалить навсегда")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun EmptyTrash(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Text(
                "Корзина пуста",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Свайпайте влево, чтобы пометить фото для удаления",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun TrashPhotoItem(
    photo: MediaPhoto,
    onRestore: () -> Unit,
    onOpen: () -> Unit,
    canRestore: Boolean = true
) {
    Box {
        AsyncImage(
            model = photo.uri,
            contentDescription = photo.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onOpen)
        )

        // Restore button in top-right corner
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(26.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
        ) {
            IconButton(
                onClick = onRestore,
                enabled = canRestore,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Восстановить",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
