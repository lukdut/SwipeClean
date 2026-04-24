package com.lukdut.swipeclean.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Sort
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size
import com.lukdut.swipeclean.data.MediaPhoto
import com.lukdut.swipeclean.data.SortOrder
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val SWIPE_THRESHOLD_FRACTION = 0.35f
private const val ROTATION_MAX_DEG = 18f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeScreen(
    viewModel: AppViewModel,
    onOpenTrash: () -> Unit
) {
    val currentPhoto by viewModel.currentPhoto.collectAsState()
    val nextPhoto by viewModel.nextPhoto.collectAsState()
    val markedCount by viewModel.markedCount.collectAsState()
    val progress by viewModel.swipeProgress.collectAsState()
    val isDone by viewModel.isDone.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val photosToPreload by viewModel.photosToPreload.collectAsState()

    var viewerPhoto by remember { mutableStateOf<MediaPhoto?>(null) }

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
            TopAppBar(
                title = { Text("SwipeClean", fontWeight = FontWeight.Bold) },
                actions = {
                    SortMenu(
                        current = sortOrder,
                        onSelect = { viewModel.setSortOrder(it) }
                    )
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
                isDone -> DoneContent(markedCount = markedCount, onOpenTrash = onOpenTrash)
                currentPhoto != null -> SwipeContent(
                    currentPhoto = currentPhoto!!,
                    nextPhoto = nextPhoto,
                    progress = progress,
                    onDelete = { viewModel.markForDeletion() },
                    onKeep = { viewModel.keep() },
                    onPhotoTap = { viewerPhoto = currentPhoto }
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
private fun SwipeContent(
    currentPhoto: MediaPhoto,
    nextPhoto: MediaPhoto?,
    progress: Pair<Int, Int>,
    onDelete: () -> Unit,
    onKeep: () -> Unit,
    onPhotoTap: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    // mutableFloatStateOf for drag tracking — updated synchronously with no coroutine, avoiding race conditions
    var dragX by remember(currentPhoto.id) { mutableStateOf(0f) }
    var dragY by remember(currentPhoto.id) { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val animX = remember(currentPhoto.id) { Animatable(0f) }
    val animY = remember(currentPhoto.id) { Animatable(0f) }
    // During drag use raw state, during animations use Animatable
    val visualX = if (isDragging) dragX else animX.value
    val visualY = if (isDragging) dragY else animY.value

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val (current, total) = progress
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${current + 1} / $total",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LinearProgressIndicator(
            progress = { if (total > 0) (current + 1).toFloat() / total else 0f },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(4.dp))
                .height(4.dp)
        )

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            val cardWidthPx = constraints.maxWidth.toFloat()
            val swipeFraction = (visualX / (cardWidthPx * SWIPE_THRESHOLD_FRACTION))
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

            // Front card — draggable
            PhotoCard(
                photo = currentPhoto,
                swipeFraction = swipeFraction,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(visualX.roundToInt(), visualY.roundToInt()) }
                    .graphicsLayer {
                        rotationZ = (visualX / cardWidthPx) * ROTATION_MAX_DEG
                    }
                    .shadow(8.dp, RoundedCornerShape(20.dp))
                    .pointerInput("tap_${currentPhoto.id}") {
                        detectTapGestures(onTap = { onPhotoTap() })
                    }
                    .pointerInput(currentPhoto.id) {
                        detectDragGestures(
                            onDragStart = {
                                isDragging = true
                                dragX = animX.value
                                dragY = animY.value
                            },
                            onDragEnd = {
                                isDragging = false
                                val threshold = cardWidthPx * SWIPE_THRESHOLD_FRACTION
                                val capturedX = dragX
                                val capturedY = dragY
                                coroutineScope.launch {
                                    when {
                                        capturedX > threshold -> {
                                            animX.snapTo(capturedX)
                                            animY.snapTo(capturedY)
                                            animX.animateTo(cardWidthPx * 1.6f, tween(280))
                                            onKeep()
                                        }
                                        capturedX < -threshold -> {
                                            animX.snapTo(capturedX)
                                            animY.snapTo(capturedY)
                                            animX.animateTo(-cardWidthPx * 1.6f, tween(280))
                                            onDelete()
                                        }
                                        else -> {
                                            animX.snapTo(capturedX)
                                            animY.snapTo(capturedY)
                                            launch {
                                                animX.animateTo(
                                                    0f, spring(stiffness = Spring.StiffnessMedium)
                                                )
                                            }
                                            launch {
                                                animY.animateTo(
                                                    0f, spring(stiffness = Spring.StiffnessMedium)
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            onDragCancel = {
                                isDragging = false
                                val capturedX = dragX
                                val capturedY = dragY
                                coroutineScope.launch {
                                    animX.snapTo(capturedX)
                                    animY.snapTo(capturedY)
                                    launch { animX.animateTo(0f) }
                                    launch { animY.animateTo(0f) }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                // Direct state update — no coroutine, no race condition
                                dragX += dragAmount.x
                                dragY += dragAmount.y
                            }
                        )
                    }
            )
        }

        // Action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FloatingActionButton(
                onClick = {
                    coroutineScope.launch {
                        animX.animateTo(animX.value - 1200f, tween(280))
                        onDelete()
                    }
                },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(68.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Удалить", modifier = Modifier.size(30.dp))
            }

            FloatingActionButton(
                onClick = {
                    coroutineScope.launch {
                        animX.animateTo(animX.value + 1200f, tween(280))
                        onKeep()
                    }
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(68.dp)
            ) {
                Icon(Icons.Default.Favorite, contentDescription = "Оставить", modifier = Modifier.size(30.dp))
            }
        }
    }

    LaunchedEffect(currentPhoto.id) {
        dragX = 0f
        dragY = 0f
        isDragging = false
        animX.snapTo(0f)
        animY.snapTo(0f)
    }
}

@Composable
private fun SortMenu(
    current: SortOrder,
    onSelect: (SortOrder) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Default.Sort, contentDescription = "Сортировка")
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
    ) {
        SortOrder.all.forEach { order ->
            DropdownMenuItem(
                text = {
                    Text(
                        order.label,
                        fontWeight = if (order == current) FontWeight.Bold else FontWeight.Normal,
                        color = if (order == current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                },
                onClick = {
                    expanded = false
                    onSelect(order)
                }
            )
        }
    }
}

@Composable
private fun PhotoCard(
    photo: MediaPhoto,
    modifier: Modifier = Modifier,
    swipeFraction: Float = 0f
) {
    Box(modifier = modifier) {
        AsyncImage(
            model = photo.uri,
            contentDescription = photo.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp))
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
