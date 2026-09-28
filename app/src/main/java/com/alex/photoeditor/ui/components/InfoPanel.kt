package com.alex.photoeditor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.alex.photoeditor.core.ImageInfo
import java.util.Locale

/** Read-only metadata panel (EXIF and file facts). */
@Composable
fun InfoPanel(info: ImageInfo?) {
    if (info == null) {
        Text(
            "No image loaded",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp)
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text("Image", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        InfoRow("Name", info.displayName)
        InfoRow("Dimensions", "${info.widthPx} x ${info.heightPx} px")
        InfoRow("Megapixels", String.format(Locale.US, "%.1f MP", info.megapixels))
        InfoRow("Aspect ratio", String.format(Locale.US, "%.3f", info.aspectRatio))
        InfoRow("Format", info.mimeType)
        InfoRow(
            "Size on disk",
            formatBytes(info.sizeBytes) + " (decoded)"
        )
        if (info.orientationDegrees != 0) {
            InfoRow("EXIF rotation", "${info.orientationDegrees}° (applied)")
        }

        if (info.cameraMake != null || info.cameraModel != null) {
            Spacer(Modifier.height(10.dp))
            Text("Camera", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            info.cameraMake?.let { InfoRow("Make", it) }
            info.cameraModel?.let { InfoRow("Model", it) }
        }

        if (info.dateTaken != null) {
            Spacer(Modifier.height(10.dp))
            Text("Capture", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            InfoRow("Date", info.dateTaken!!)
        }

        Spacer(Modifier.height(10.dp))
        Text("Location", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        if (info.gpsPresent && info.gpsLat != null && info.gpsLon != null) {
            InfoRow(
                "GPS",
                String.format(Locale.US, "%.5f, %.5f", info.gpsLat, info.gpsLon)
            )
            Text(
                "GPS is removed from exported files.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            InfoRow("GPS", "Not present")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .semantics { contentDescription = "$label: $value" },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000f)
    bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1_000f)
    else -> "$bytes B"
}
