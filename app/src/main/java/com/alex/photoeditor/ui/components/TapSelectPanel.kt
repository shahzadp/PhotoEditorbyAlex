package com.alex.photoeditor.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alex.photoeditor.core.TapAnchor
import com.alex.photoeditor.ui.EditorViewModel

/**
 * "Tap to select" tool.
 *
 * The user taps the photo; MediaPipe segments whatever is under that point and
 * the background is made transparent. Additional taps extend the selection;
 * a long press starts over.
 */
@Composable
fun TapSelectPanel(
    vm: EditorViewModel,
    bitmap: Bitmap?
) {
    val cutout = vm.currentCutout()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(bottom = 12.dp)
    ) {
        TapSelectStage(
            bitmap = bitmap,
            anchors = cutout.anchors,
            onTap = { x, y, replace -> vm.selectAt(x, y, replace) }
        )

        Spacer(Modifier.height(4.dp))

        // Keep / remove and clear controls.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToggleChip(
                text = "Keep subject",
                selected = cutout.keepSubject,
                onClick = { vm.setKeepSubject(!cutout.keepSubject) }
            )
            ToggleChip(
                text = "Add to selection",
                selected = cutout.additive,
                onClick = { /* hint only; tap behaviour is on the stage */ },
                enabled = false
            )
            Spacer(Modifier.weight(1f))
            if (cutout.isActive) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { vm.clearCutout() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "Clear",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(2.dp))

        LabelledSlider(
            label = "Edge threshold",
            value = cutout.threshold,
            range = 0.05f..0.95f,
            display = "%.2f",
            onChange = vm::setCutoutThreshold
        )

        LabelledSlider(
            label = "Edge softness",
            value = cutout.feather,
            range = 0f..0.4f,
            display = "%.2f",
            onChange = vm::setCutoutFeather
        )

        if (!cutout.isActive) {
            Text(
                text = "Tap the photo to select an object",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun ToggleChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = text }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = (if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }).copy(alpha = alpha)
        )
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                display.format(value),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range
        )
    }
}

/**
 * The tappable photo.
 *
 * Uses the same fitted-size approach as [CropStage] so the image is rendered
 * at its true on-screen rectangle; a tap is converted to normalised image
 * coordinates against exactly that rectangle, otherwise the selection would
 * drift with the letterboxing.
 */
@Composable
private fun TapSelectStage(
    bitmap: Bitmap?,
    anchors: List<TapAnchor>,
    onTap: (x: Float, y: Float, replace: Boolean) -> Unit,
    padding: Dp = 12.dp
) {
    if (bitmap == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No image loaded",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        val ar = bitmap.width.toFloat() / bitmap.height.toFloat()
        // Dp cannot be compared directly, so the fit is done in float space and
        // converted back to Dp once.
        val maxWf = maxWidth.value - padding.value * 2f
        val maxHf = maxHeight.value - padding.value * 2f
        val imgWv = if (ar * maxHf > maxWf) maxWf else maxHf * ar
        val imgHv = imgWv / ar

        val imgW = imgWv.dp
        val imgH = imgHv.dp

        Box(
            modifier = Modifier
                .width(imgW)
                .height(imgH)
                .clip(RoundedCornerShape(4.dp))
                .pointerInput(bitmap.width, bitmap.height, anchors.size) {
                    detectTapGestures(
                        onLongPress = { pos ->
                            val x = (pos.x / size.width).coerceIn(0f, 1f)
                            val y = (pos.y / size.height).coerceIn(0f, 1f)
                            onTap(x, y, true)
                        },
                        onTap = { pos ->
                            val x = (pos.x / size.width).coerceIn(0f, 1f)
                            val y = (pos.y / size.height).coerceIn(0f, 1f)
                            onTap(x, y, false)
                        }
                    )
                }
                .semantics { contentDescription = "Tap the photo to select an object" }
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )

            // Markers for the taps that built the current selection, so the
            // user can see what has been picked and long-press to restart.
            if (anchors.isNotEmpty()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    anchors.forEach { anchor ->
                        drawCircle(
                            color = Color(0xFF06D6A0),
                            radius = 10f,
                            center = Offset(anchor.x * size.width, anchor.y * size.height)
                        )
                        drawCircle(
                            color = Color(0xFF101014),
                            radius = 4f,
                            center = Offset(anchor.x * size.width, anchor.y * size.height)
                        )
                    }
                }
            }
        }
    }
}
