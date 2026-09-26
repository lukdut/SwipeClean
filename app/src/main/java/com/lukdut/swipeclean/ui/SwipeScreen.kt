package com.lukdut.swipeclean.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size
import com.lukdut.swipeclean.data.MediaPhoto
import com.lukdut.swipeclean.data.SortOrder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val SWIPE_THRESHOLD_FRACTION = 0.35f
private const val ROTATION_MAX_DEG = 18f
private const val DISMISS_DURATION_MS = 280

private data class DismissedPhoto(
    val photo: MediaPhoto,
    val startOffset: Offset,
    val keep: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeScreen(
    viewModel: AppViewModel,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAnalysis: () -> Unit
) {
    val currentPhoto by viewModel.currentPhoto.collectAsState()
    val nextPhoto by viewModel.nextPhoto.collectAsState()
    val markedCount by viewModel.markedCount.collectAsState()
    val progress by viewModel.swipeProgress.collectAsState()
    val isDone by viewModel.isDone.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val photosToPreload by viewModel.photosToPreload.collectAsState()
    val analysis by viewModel.analysisProgress.collectAsState()
    val hasAnalysisResults by viewModel.hasAnalysisResults.collectAsState()
    val priorityReason by viewModel.priorityReason.collectAsState()
    val loadError by viewModel.loadError.collectAsState()

    var viewerPhoto by remember { mutableStateOf<MediaPhoto?>(null) }

    DisposableEffect(viewModel) {
        viewModel.setReviewVisible(true)
        onDispose { viewModel.setReviewVisible(false) }
    }

    val context = LocalContext.current
    LaunchedEffect(photosToPreload) {
        photosToPreload.forEach { photo ->
            val request = ImageRequest.Builder(context)
                .data(photo.uri)
                .size(Size.ORIGINAL)
                .memoryCacheKey(photo.uri.toString())
                .build()
            context.imageLoader.enqueue(request)
        }
    }

    viewerPhoto?.let { photo ->
        PhotoViewerDialog(
            photo = photo,
            onDismiss = { viewerPhoto = null }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Настройки")
                        }
                    },
                    title = {
                        SortMenu(
                            current = sortOrder,
                            hasAnalysisResults = hasAnalysisResults,
                            onSelect = viewModel::setSortOrder,
                            onOpenAnalysis = onOpenAnalysis
                        )
                    },
                    actions = {
                        BadgedBox(
                            badge = {
                                if (markedCount > 0) {
                                    Badge { Text(markedCount.toString()) }
                                }
                            }
                        ) {
                            IconButton(onClick = onOpenTrash) {
                                Icon(Icons.Default.Delete, contentDescription = "Корзина")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                if (sortOrder == SortOrder.ByPotentiallyUnwanted || analysis.running) {
                    val completed = analysis.analyzed + analysis.skipped
                    val percent = if (analysis.total > 0) completed.toLong() * 100 / analysis.total else 0
                    val status = if (!analysis.running && analysis.remaining > 0) "приостановлено" else "$percent%"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button, onClickLabel = "Открыть настройки анализа",
                                onClick = onOpenAnalysis)
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Анализ: $completed из ${analysis.total} ($status)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> CircularProgressIndicator()
                loadError != null -> Column(Modifier.padding(24.dp)) {
                    Text(loadError!!)
                    TextButton(onClick = viewModel::loadPhotos) { Text("Повторить") }
                }
                isDone -> DoneContent(markedCount = markedCount, onOpenTrash = onOpenTrash)
                currentPhoto != null -> SwipeContent(
                    currentPhoto = currentPhoto!!,
                    nextPhoto = nextPhoto,
                    progress = progress,
                    onDelete = { viewModel.markForDeletion() },
                    onKeep = { viewModel.keep() },
                    onPhotoTap = { viewerPhoto = currentPhoto },
                    priorityReason = priorityReason
                )
                else -> EmptyContent()
            }
        }
    }
}

@Composable
private fun EmptyContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Text(
            "Фото не найдены",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DoneContent(markedCount: Int, onOpenTrash: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(32.dp)
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            "Все фото просмотрены!",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        if (markedCount > 0) {
            Text(
                "$markedCount фото помечено для удаления",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.material3.Button(onClick = onOpenTrash) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Открыть корзину")
            }
        } else {
            Text(
                "Нет фото для удаления",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun SwipeContent(
    currentPhoto: MediaPhoto,
    nextPhoto: MediaPhoto?,
    progress: Pair<Int, Int>,
    onDelete: () -> Unit,
    onKeep: () -> Unit,
    onPhotoTap: () -> Unit,
    priorityReason: String? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val dismissedPhotos = remember { mutableStateListOf<DismissedPhoto>() }
    var dragOffset by remember(currentPhoto.id) { mutableStateOf(Offset.Zero) }
    var isDragging by remember(currentPhoto.id) { mutableStateOf(false) }
    var isDismissed by remember(currentPhoto.id) { mutableStateOf(false) }
    var returnAnimation by remember(currentPhoto.id) { mutableStateOf<Job?>(null) }
    val animatedOffset = remember(currentPhoto.id) { Animatable(Offset.Zero, Offset.VectorConverter) }
    val visualOffset = if (isDragging) dragOffset else animatedOffset.value

    fun dismissPhoto(keep: Boolean) {
        if (isDismissed) return
        isDismissed = true
        returnAnimation?.cancel()
        dismissedPhotos += DismissedPhoto(
            photo = currentPhoto,
            startOffset = if (isDragging) dragOffset else animatedOffset.value,
            keep = keep
        )
        // Advance immediately; the outgoing card finishes its animation independently.
        if (keep) onKeep() else onDelete()
    }

    fun returnToCenter() {
        if (isDismissed) return
        val startOffset = dragOffset
        returnAnimation?.cancel()
        returnAnimation = coroutineScope.launch {
            animatedOffset.snapTo(startOffset)
            isDragging = false
            animatedOffset.animateTo(Offset.Zero, spring(stiffness = Spring.StiffnessMedium))
        }
    }

    DisposableEffect(currentPhoto.id) {
        onDispose { returnAnimation?.cancel() }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val (current, total) = progress
        LinearProgressIndicator(
            progress = { if (total > 0) (current + 1).toFloat() / total else 0f },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .height(4.dp)
        )

        priorityReason?.let {
            Text(
                it,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            val cardWidthPx = constraints.maxWidth.toFloat()
            val swipeFraction = (visualOffset.x / (cardWidthPx * SWIPE_THRESHOLD_FRACTION))
                .coerceIn(-1f, 1f)

            // Back card — scale up as the front card moves away
            if (nextPhoto != null) {
                val backScale = 0.92f + 0.08f * abs(swipeFraction).coerceIn(0f, 1f)
                PhotoCard(
                    photo = nextPhoto,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { scaleX = backScale; scaleY = backScale }
                )
            }

            // Keep the touch target still while the photo moves underneath the finger.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput("tap_${currentPhoto.id}") {
                        detectTapGestures(onTap = { if (!isDismissed) onPhotoTap() })
                    }
                    .pointerInput(currentPhoto.id, cardWidthPx) {
                        detectDragGestures(
                            onDragStart = {
                                returnAnimation?.cancel()
                                if (!isDragging) dragOffset = animatedOffset.value
                                isDragging = true
                            },
                            onDragEnd = {
                                val threshold = cardWidthPx * SWIPE_THRESHOLD_FRACTION
                                when {
                                    dragOffset.x > threshold -> dismissPhoto(keep = true)
                                    dragOffset.x < -threshold -> dismissPhoto(keep = false)
                                    else -> returnToCenter()
                                }
                            },
                            onDragCancel = { returnToCenter() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffset += dragAmount
                            }
                        )
                    }
            ) {
                if (!isDismissed) {
                    PhotoCard(
                        photo = currentPhoto,
                        swipeFraction = swipeFraction,
                        modifier = Modifier
                            .fillMaxSize()
                            .offset { IntOffset(visualOffset.x.roundToInt(), visualOffset.y.roundToInt()) }
                            .graphicsLayer {
                                rotationZ = (visualOffset.x / cardWidthPx) * ROTATION_MAX_DEG
                            }
                    )
                }
            }

            dismissedPhotos.forEach { dismissed ->
                key(dismissed.photo.id) {
                    DismissedPhotoCard(
                        dismissed = dismissed,
                        cardWidthPx = cardWidthPx,
                        onFinished = { dismissedPhotos.remove(dismissed) }
                    )
                }
            }
        }

        // Action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FloatingActionButton(
                onClick = { dismissPhoto(keep = false) },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(60.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Удалить", modifier = Modifier.size(28.dp))
            }

            Text(
                text = "${current + 1} / $total",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FloatingActionButton(
                onClick = { dismissPhoto(keep = true) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(60.dp)
            ) {
                Icon(Icons.Default.Favorite, contentDescription = "Оставить", modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun DismissedPhotoCard(
    dismissed: DismissedPhoto,
    cardWidthPx: Float,
    onFinished: () -> Unit
) {
    val offsetX = remember { Animatable(dismissed.startOffset.x) }
    val direction = if (dismissed.keep) 1f else -1f

    LaunchedEffect(dismissed, cardWidthPx) {
        offsetX.animateTo(direction * cardWidthPx * 1.6f, tween(DISMISS_DURATION_MS))
        onFinished()
    }

    PhotoCard(
        photo = dismissed.photo,
        swipeFraction = (offsetX.value / (cardWidthPx * SWIPE_THRESHOLD_FRACTION)).coerceIn(-1f, 1f),
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(offsetX.value.roundToInt(), dismissed.startOffset.y.roundToInt()) }
            .graphicsLayer { rotationZ = (offsetX.value / cardWidthPx) * ROTATION_MAX_DEG }
            .clearAndSetSemantics { }
    )
}

@Composable
internal fun SortMenu(
    current: SortOrder,
    hasAnalysisResults: Boolean,
    onSelect: (SortOrder) -> Unit,
    onOpenAnalysis: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics {
                contentDescription = "Сортировка"
                stateDescription = current.label
            }
        ) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                current.label,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            SortOrder.all.forEach { order ->
                val needsAnalysis = order == SortOrder.ByPotentiallyUnwanted && !hasAnalysisResults
                DropdownMenuItem(
                    text = {
                        Text(
                            order.label,
                            fontWeight = if (order == current) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                order == current -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                    },
                    modifier = Modifier.semantics {
                        if (needsAnalysis) stateDescription = "Сначала выполните анализ в настройках"
                    },
                    onClick = {
                        expanded = false
                        if (needsAnalysis) onOpenAnalysis() else onSelect(order)
                    }
                )
            }
        }
    }
}

@Composable
private fun PhotoCard(
    photo: MediaPhoto,
    modifier: Modifier = Modifier,
    swipeFraction: Float = 0f
) {
    Box(
        // Hide the next photo behind the fitted image's margins without a visible card frame.
        modifier = modifier.background(MaterialTheme.colorScheme.background)
    ) {
        AsyncImage(
            model = photo.uri,
            contentDescription = photo.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        // Delete overlay (swipe left)
        if (swipeFraction < -0.05f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Color(0xFFE53935).copy(alpha = (-swipeFraction).coerceIn(0f, 0.75f))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = (-swipeFraction * 1.5f).coerceIn(0f, 1f)),
                    modifier = Modifier.size(88.dp)
                )
            }
        }

        // Keep overlay (swipe right)
        if (swipeFraction > 0.05f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Color(0xFF43A047).copy(alpha = swipeFraction.coerceIn(0f, 0.75f))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = (swipeFraction * 1.5f).coerceIn(0f, 1f)),
                    modifier = Modifier.size(88.dp)
                )
            }
        }
    }
}
