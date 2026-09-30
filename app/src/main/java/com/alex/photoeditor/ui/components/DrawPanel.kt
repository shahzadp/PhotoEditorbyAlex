package com.alex.photoeditor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alex.photoeditor.core.ColourUtils
import com.alex.photoeditor.core.DrawBrushes
import com.alex.photoeditor.ui.EditorViewModel
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The draw tool's panel.
 *
 * Structurally a sibling of [TextPanel]: a mode row, a brush row, a size
 * slider, then the shared [ColourPicker] behind a toggle. Deliberately the
 * same shape, because the two panels sit in the same slot in the screen and
 * anything that differs reads as a different app.
 *
 * The brush colour is *not* per-stroke the way a text colour is per-object.
 * Drawing tools treat colour as a property of the tool, and changing it
 * recolours what is already on the canvas - which is what people expect when
 * they pick a colour after drawing something in the wrong one.
 */
@Composable
fun DrawPanel(
    vm: EditorViewModel,
    modifier: Modifier = Modifier
) {
    val colour = vm.drawColor
    val erasing = vm.drawErase
    val fillMode = vm.drawFillMode
    val eyedropperOn = vm.drawEyedropperActive
    val hasDrawing = vm.hasDrawing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(bottom = 10.dp)
    ) {

        // --- Mode: draw / erase / fill / eyedropper
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ExpressiveChip(
                label = "Draw",
                selected = !erasing && !fillMode,
                onClick = { vm.setErasing(false); vm.setFillArmed(false) },
                marked = false
            )
            ExpressiveChip(
                label = "Erase",
                selected = erasing,
                onClick = { vm.setErasing(!erasing) },
                marked = false
            )
            ExpressiveChip(
                label = "Fill",
                selected = fillMode,
                onClick = { vm.setFillArmed(!fillMode) },
                marked = false
            )
            Spacer(Modifier.weight(1f))
            ExpressiveIconToggle(
                icon = Icons.Filled.Colorize,
                contentDescription = "Pick a brush colour from the photo",
                selected = eyedropperOn,
                onClick = { vm.setDrawEyedropper(!eyedropperOn) }
            )
        }

        Spacer(Modifier.height(10.dp))

        // --- Brush styles. Each is previewed by its own mark rather than
        // named, because "Round" versus "Dotted" is easier to recognise than
        // to read.
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(DrawBrushes.options) { option ->
                val selected = option.key == vm.drawBrushKey && !erasing && !fillMode
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            }
                        )
                        .clickable { vm.setDrawBrush(option.key) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    BrushSwatch(brushKey = option.key, erasing = erasing)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // --- Size. Disabled for the fill, which has no brush.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Size",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = vm.drawWidthFraction,
                onValueChange = { vm.setDrawWidth(it) },
                // A log-ish feel: the useful range spans roughly 1% to 20% of
                // the short edge, and a linear slider spends most of its
                // travel in a range nobody draws at.
                valueRange = 0.0015f..0.12f,
                enabled = !fillMode,
                colors = SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            )
            Text(
                "${(vm.drawWidthFraction * 1000).roundToInt() / 10f}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(Modifier.height(4.dp))

        // --- Colour + hex, then the wheel behind a toggle.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ColourUtils.fromArgbLong(colour))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(10.dp)
                    )
            )
            Spacer(Modifier.size(10.dp))
            HexField(color = colour, onColorChange = { vm.setBrushColor(it) })
        }

        Spacer(Modifier.height(6.dp))

        // Declared out here rather than inside the Row below: the flag has to outlive
        // that Row so the ColourPicker underneath can read the same value.
        var showPicker by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
        ) {
            ExpressiveOutlinedButton(
                text = if (showPicker) "Hide colours" else "Colour wheel",
                onClick = { showPicker = !showPicker },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.size(8.dp))
            ExpressiveOutlinedButton(
                text = if (hasDrawing) "Clear all" else "Nothing yet",
                onClick = { vm.clearDrawing() },
                enabled = hasDrawing,
                modifier = Modifier.weight(1f)
            )
        }

        if (showPicker) {
            ColourPicker(
                color = colour,
                onColorChange = { vm.setBrushColor(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        if (eyedropperOn) {
            Text(
                text = "Tap anywhere on the photo to pick that colour",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        } else if (fillMode) {
            Text(
                text = "Tap a region to fill it. Drag to paint instead.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * A small mark showing what a brush style does.
 *
 * Drawn rather than labelled: the difference between a dotted and a dashed
 * stroke is instantly recognisable as a shape and slow to read as a word.
 */
@Composable
private fun BrushSwatch(brushKey: String, erasing: Boolean) {
    val tint = if (erasing) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    val stroke = 2.dp
    Canvas(modifier = Modifier.size(width = 28.dp, height = 16.dp)) {
        val cy = size.height / 2f
        val path = Path().apply {
            moveTo(2f, cy)
            cubicTo(
                size.width * 0.35f, cy - size.height * 0.42f,
                size.width * 0.65f, cy + size.height * 0.42f,
                size.width - 2f, cy
            )
        }

        // An airbrush is drawn as discrete dots rather than a stroke; the other
        // four differ only in their dash pattern.
        if (brushKey == "spray") {
            for (i in 0 until 6) {
                val t = i / 5f
                val x = 3f + t * (size.width - 6f)
                val y = cy + sin(t * 6.2f) * size.height * 0.32f
                drawCircle(
                    color = tint,
                    radius = 1.5f + (i % 2),
                    center = Offset(x, y)
                )
            }
            return@Canvas
        }

        drawPath(
            path = path,
            color = tint,
            style = Stroke(
                width = stroke.toPx(),
                cap = StrokeCap.Round,
                pathEffect = when (brushKey) {
                    "dashed" -> PathEffect.dashPathEffect(
                        floatArrayOf(stroke.toPx() * 2.4f, stroke.toPx() * 1.4f)
                    )
                    "dotted" -> PathEffect.dashPathEffect(
                        floatArrayOf(0.01f, stroke.toPx() * 1.6f)
                    )
                    else -> null
                }
            )
        )
    }
}