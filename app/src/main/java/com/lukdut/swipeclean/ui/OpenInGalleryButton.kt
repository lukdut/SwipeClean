package com.lukdut.swipeclean.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lukdut.swipeclean.data.MediaPhoto

@Composable
internal fun OpenInGalleryButton(
    photo: MediaPhoto,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val context = LocalContext.current
    IconButton(
        onClick = {
            val intent = Intent(Intent.ACTION_VIEW, photo.uri).apply {
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(Intent.createChooser(intent, null))
        },
        enabled = enabled,
        modifier = modifier
            .size(40.dp)
            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
    ) {
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = "Открыть в галерее",
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
    }
}
