package com.lukdut.swipeclean.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.min

internal class PhotoZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var imageSize = Size.Unspecified
    // Ignore rounding noise from a two-finger pan at the original size.
    val isZoomed: Boolean get() = scale > 1.001f

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    fun transform(centroid: Offset, pan: Offset, zoom: Float, viewport: IntSize) {
        val newScale = (scale * zoom).coerceIn(1f, 5f)
        val focus = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
        // Keep the image point beneath the fingers fixed as the scale changes.
        val newOffset = focus + (offset - focus) * (newScale / scale) + pan
        val fittedSize = if (imageSize != Size.Unspecified && imageSize.width > 0f && imageSize.height > 0f) {
            imageSize * min(viewport.width / imageSize.width, viewport.height / imageSize.height)
        } else {
            Size(viewport.width.toFloat(), viewport.height.toFloat())
        }
        val maxX = ((fittedSize.width * newScale - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((fittedSize.height * newScale - viewport.height) / 2f).coerceAtLeast(0f)
        offset = if (newScale == 1f) Offset.Zero else Offset(
            newOffset.x.coerceIn(-maxX, maxX),
            newOffset.y.coerceIn(-maxY, maxY)
        )
        scale = newScale
    }
}

/** A gesture stays in zoom/pan mode until every finger is lifted, even after zooming out. */
internal suspend fun PointerInputScope.detectPhotoGestures(
    isZoomed: () -> Boolean,
    onTransformStart: () -> Unit,
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var transforming = isZoomed()
        var transformClaimed = false
        var dragging = false
        var totalDrag = Offset.Zero
        try {
            do {
                val event = awaitPointerEvent()
                if (event.changes.any { it.isConsumed }) break

                if (event.changes.count { it.pressed } > 1) {
                    if (!transforming) {
                        transforming = true
                        dragging = false
                        onTransformStart()
                    }
                    // Two fingers claim the gesture even without movement.
                    transformClaimed = true
                }

                val pan = event.calculatePan()
                totalDrag += pan
                if (transforming) {
                    // Leave a single-finger tap available to the tap detector while zoomed.
                    if (totalDrag.getDistance() > viewConfiguration.touchSlop) transformClaimed = true
                    if (transformClaimed) {
                        val centroid = event.calculateCentroid(useCurrent = false)
                        if (centroid != Offset.Unspecified) {
                            onTransform(centroid, pan, event.calculateZoom())
                        }
                        event.changes.forEach { it.consume() }
                    }
                } else {
                    if (!dragging && totalDrag.getDistance() > viewConfiguration.touchSlop) {
                        dragging = true
                        onDragStart()
                        onDrag(totalDrag * (1f - viewConfiguration.touchSlop / totalDrag.getDistance()))
                    } else if (dragging) {
                        onDrag(pan)
                    }
                    if (dragging) {
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }

                if (event.changes.none { it.pressed }) {
                    if (dragging) {
                        dragging = false
                        onDragEnd()
                    }
                    break
                }
            } while (true)
        } finally {
            if (dragging) onDragCancel()
        }
    }
}
