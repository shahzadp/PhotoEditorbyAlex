package com.alex.photoeditor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alex.photoeditor.core.ColourUtils
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Colour picker with a hex field, an HSV wheel and a swatch row.
 *
 * Shared by text colour and the upcoming draw tool, so the two cannot drift
 * apart in behaviour or appearance.
 *
 * The wheel is the standard hue/saturation disc: hue runs around the angle,
 * saturation runs from the centre outward. Value (brightness) is deliberately
 * *not* on the wheel — it is the thing people most often get wrong when it
 * shares an axis, and it already has a natural home as the 3D lightness
 * slider below, which is kept deliberately small.
 */
@Composable
fun ColourPicker(
    color: Long,
    onColorChange: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var wheelSize by remember { mutableStateOf(0) }

    val (h, s, v) = remember(color) { ColourUtils.argbToHsv(color) }
    val swatches = remember { ColourUtils.swatches(12) }

    Column(modifier = modifier) {
        // --- Swatches
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(swatches) { swatch ->
                val isSelected = swatch == color
                Box(
                    modifier = Modifier
                        .size(if (isSelected) 30.dp else 26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(ColourUtils.fromArgbLong(swatch))
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = RoundedCornerShape(13.dp)
                        )
                        .clickable { onColorChange(swatch) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // --- Wheel
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .size(160.dp)
                    .clip(RoundedCornerShape(80.dp))
                    .pointerInput(Unit) {
                        // PointerInputScope exposes an IntSize, so the
                        // shortest edge comes from minOf on its two components
                        // rather than the DrawScope-only minDimension.
                        val radius = minOf(size.width, size.height) / 2f
                        val centre = Offset(size.width / 2f, size.height / 2f)

                        fun pick(at: Offset): Pair<Float, Float> {
                            val dx = at.x - centre.x
                            val dy = at.y - centre.y
                            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                            // Hue from the angle, with -90 so 0deg sits at the
                            // top rather than to the right.
                            val hue = (
                                (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90.0) / 360.0
                                ).toFloat().let { ((it % 1f) + 1f) % 1f }
                            // Clamp rather than normalise: dragging past the rim
                            // should stay at full saturation, not wrap back
                            // toward the centre and desaturate.
                            val sat = (dist / radius).coerceIn(0f, 1f)
                            return hue to sat
                        }

                        detectTapGestures { at ->
                            val (hh, ss) = pick(at)
                            onColorChange(ColourUtils.hsvToArgb(hh, ss, v))
                        }
                    }                    .pointerInput(Unit) {
                        val radius = minOf(size.width, size.height) / 2f
                        val centre = Offset(size.width / 2f, size.height / 2f)

                        fun pick(at: Offset): Pair<Float, Float> {
                            val dx = at.x - centre.x
                            val dy = at.y - centre.y
                            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                            val hue = (
                                (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90.0) / 360.0
                                ).toFloat().let { ((it % 1f) + 1f) % 1f }
                            val sat = (dist / radius).coerceIn(0f, 1f)
                            return hue to sat
                        }

                        detectDragGestures(
                            onDragStart = { at ->
                                val (hh, ss) = pick(at)
                                onColorChange(ColourUtils.hsvToArgb(hh, ss, v))
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val (hh, ss) = pick(change.position)
                                onColorChange(ColourUtils.hsvToArgb(hh, ss, v))
                            }
                        )
                    }
            ) {
                val radius = size.minDimension / 2f
                val centre = Offset(size.width / 2f, size.height / 2f)

                // Hue ring: a conic sweep of fully saturated colours. Drawn as
                // a sweep of segments rather than a real conic gradient
                // because that renders identically on every API level.
                val segments = 60
                for (i in 0 until segments) {
                    val startAngle = (i / segments.toFloat()) * 360f
                    val hue = ColourUtils.hsvToArgb(startAngle / 360f, 1f, 1f)
                    drawArc(
                        color = ColourUtils.fromArgbLong(hue),
                        startAngle = startAngle,
                        sweepAngle = 360f / segments + 1f,
                        useCenter = true,
                        topLeft = Offset(0f, 0f),
                        size = size
                    )
                }

                // Punch the middle out so only the ring remains.
                drawCircle(Color.Transparent, radius, centre, style = Stroke(width = radius))

                // Saturation disc for the current hue: white at the centre,
                // fully saturated at the edge.
                val currentHue = ColourUtils.hsvToArgb(h, 1f, 1f)
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to Color.White,
                        1f to ColourUtils.fromArgbLong(currentHue),
                        center = centre,
                        radius = radius
                    ),
                    radius = radius,
                    center = centre
                )

                // Selection marker: angle is hue, distance is saturation.
                val angleRad = Math.toRadians((h * 360f - 90f).toDouble())
                val marker = Offset(
                    centre.x + (radius * s * cos(angleRad)).toFloat(),
                    centre.y + (radius * s * sin(angleRad)).toFloat()
                )
                drawCircle(
                    color = Color.White,
                    radius = 12f,
                    center = marker,
                    style = Stroke(width = 3f)
                )
                drawCircle(
                    color = ColourUtils.fromArgbLong(ColourUtils.hsvToArgb(h, s, v)),
                    radius = 8f,
                    center = marker
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // --- Value
        ValueSlider(value = v, onValueChange = { onColorChange(ColourUtils.hsvToArgb(h, s, it)) })
    }
}

/**
 * Brightness slider.
 *
 * Kept compact and separate from the wheel because value is the axis people
 * most often misread when it is folded into a two-dimensional control.
 */
@Composable
private fun ValueSlider(value: Float, onValueChange: (Float) -> Unit) {
    androidx.compose.material3.Slider(
        value = value.coerceIn(0f, 1f),
        onValueChange = onValueChange,
        valueRange = 0f..1f,
        colors = androidx.compose.material3.SliderDefaults.colors(
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
    )
}

/**
 * Hex text field.
 *
 * The field does not reformat as the user types. A field that rewrote itself
 * mid-keystroke fights the cursor and made it impossible to type a value
 * digit by digit, so the committed value is only reflected back once the input
 * parses.
 */
@Composable
fun HexField(
    color: Long,
    onColorChange: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember(color) { mutableStateOf(ColourUtils.toHex(color)) }
    var isValid by remember { mutableStateOf(true) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ColourUtils.fromArgbLong(color))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(10.dp)
                )
        )

        Spacer(Modifier.size(10.dp))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = { entered ->
                    text = entered
                    val parsed = ColourUtils.parseHex(entered)
                    isValid = parsed != null
                    parsed?.let(onColorChange)
                },
                singleLine = true,
                textStyle = TextStyle(
                    color = if (isValid) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.width(IntrinsicHexWidth)
            )
        }
    }
}

/** A width that fits a hex value without letting the field stretch. */
private val IntrinsicHexWidth = 96.dp