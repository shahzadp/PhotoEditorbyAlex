package com.alex.photoeditor.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alex.photoeditor.core.ColourUtils
import com.alex.photoeditor.core.DrawBrushes
import com.alex.photoeditor.core.Eyedropper
import com.alex.photoeditor.core.StrokePoint
import com.alex.photoeditor.ui.EditorViewModel
import kotlin.math.sqrt

/**
 * The drawing surface.
 *
 * Handles three gestures, picked by what the draw panel has armed:
 *  - a drag paints or erases a stroke
 *  - a tap fills the region under the finger
 *  - a tap with the eyedropper on samples a colour
 *
 * The stroke follows the finger from *local* state and is committed to the
 * repository on pointer-up. Committing per event instead would push a history
 * entry per pixel of movement and make a single drag need hundreds of undos;
 * holding it locally keeps the drag visually live, while the autosave debounce
 * still decides when anything reaches disk.
 */
@Composable
fun DrawGestureStage(
    bitmap: Bitmap?,
    vm: EditorViewModel,
    padding: Dp = 12.dp,
    modifier: Modifier = Modifier
) {
    if (bitmap == null) {
        Box(modifier = modifier.fillMaxSize())
        return
    }

    val eyedropperOn = vm.drawEyedropperActive
    val fillMode = vm.drawFillMode
    val erase = vm.drawErase
    val colour = vm.drawColor
    val brushKey = vm.drawBrushKey
    val widthFraction = vm.drawWidthFraction
    val opacity = vm.drawOpacity

    // The in-progress stroke, in image-normalised coordinates.
    var livePoints by remember { mutableStateOf<List<StrokePoint>>(emptyList()) }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val maxW = maxWidth.value - padding.value * 2f
        val maxH = maxHeight.value - padding.value * 2f
        val ar = bitmap.width.toFloat() / bitmap.height.toFloat()
        val imgWv = if (ar * maxH > maxW) maxW else maxH * ar
        val imgHv = imgWv / ar

        // Points arrive in the *stage's* space, so map them into image space
        // here. Without this the stroke would be anchored to the preview box
        // rather than to the photo, and would sit in the wrong place at export
        // resolution.
        fun toImage(at: Offset, w: Float, h: Float): StrokePoint = StrokePoint(
            (at.x / w).coerceIn(0f, 1f),
            (at.y / h).coerceIn(0f, 1f)
        )

        Box(
            modifier = Modifier
                .width(imgWv.dp)
                .height(imgHv.dp)
                .background(ComposeColor.Transparent)
                .pointerInput(eyedropperOn, fillMode, erase, colour, brushKey, widthFraction, opacity) {
                    val w = size.width.toFloat().takeIf { it > 0f } ?: 1f
                    val h = size.height.toFloat().takeIf { it > 0f } ?: 1f
                    val slop = viewConfiguration.touchSlop

                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        val start = toImage(first.position, w, h)

                        // --- one-shot gestures
                        if (eyedropperOn) {
                            Eyedropper.sample(bitmap, start.x, start.y)?.let { vm.applyDrawSample(it) }
                            vm.setDrawEyedropper(false)
                            return@awaitEachGesture
                        }

                        // --- fill: a tap fills, a drag paints.
                        //
                        // Deciding after the fact rather than on the first move
                        // means the tap does not have to be instantaneous; a
                        // finger that rests before lifting still counts as a
                        // tap, which is what people expect.
                        var becameDrag = false
                        if (fillMode) {
                            var released = false
                            while (!released) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == first.id } ?: break
                                if (!change.pressed) {
                                    released = true
                                    break
                                }
                                if ((change.position - first.position).getDistance() > slop * 2f) {
                                    becameDrag = true
                                    break
                                }
                                change.consume()
                            }
                            if (!becameDrag) {
                                vm.fillAt(start.x, start.y)
                                vm.setFillArmed(false)
                                return@awaitEachGesture
                            }
                        }

                        // --- paint / erase
                        val id = vm.beginStroke()
                        var previous = start
                        var carry = 0f
                        val batch = ArrayList<StrokePoint>(64)
                        batch.add(start)
                        livePoints = listOf(start)

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == first.id } ?: break
                            if (!change.pressed) break

                            val at = toImage(change.position, w, h)
                            // Only record a point once the finger has actually
                            // travelled. Without this a slow drag at preview
                            // resolution emits hundreds of near-identical
                            // points, which bloats the saved project and slows
                            // every re-render for no visible gain.
                            carry += hypot(
                                (at.x - previous.x) * bitmap.width,
                                (at.y - previous.y) * bitmap.height
                            )
                            if (carry >= MIN_POINT_SPACING_PX) {
                                batch.add(at)
                                previous = at
                                carry = 0f
                                livePoints = livePoints + at
                            }
                            change.consume()
                        }

                        if (batch.isNotEmpty()) vm.extendStroke(id, batch)
                        livePoints = emptyList()
                        vm.endStroke()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Edited photo preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "Photo preview" }
            )

            // The live stroke, drawn over the photo so it tracks the finger
            // exactly rather than waiting for the pipeline to come back.
            if (livePoints.isNotEmpty()) {
                LiveStrokeOverlay(
                    points = livePoints,
                    colorArgb = if (erase) 0x00000000L else colour,
                    widthFraction = widthFraction,
                    brushKey = brushKey,
                    opacity = opacity,
                    erasing = erase,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height
                )
            }
        }
    }
}

/**
 * Live stroke preview.
 *
 * Drawn with Compose rather than by pushing pixels through the pipeline, so it
 * updates on the same frame as the finger. The committed stroke comes back
 * through `DrawComposer` a frame or two later, which is close enough that the
 * two are indistinguishable.
 *
 * An eraser is shown as a *ring at the brush diameter* rather than as a
 * transparent line. Two reasons, and the second is the important one:
 *
 *  1. A fully transparent stroke is invisible, so an eraser preview would show
 *     nothing at all and the size slider would appear to do nothing.
 *  2. Even if it were visible, the overlay is composited on top of the photo,
 *     so it can never actually remove ink while the finger is down. Only the
 *     committed erase stroke can, via `PorterDuff.CLEAR`. A ring is an honest
 *     "this is where and how wide it will erase", rather than a fake preview
 *     that suggests the pixels underneath are already gone.
 *
 * Spray is also shown as a plain ring for the same reason the live stroke is
 * simplified: stamping dots at preview rate would cost more than the render.
 */
@Composable
private fun LiveStrokeOverlay(
    points: List<StrokePoint>,
    colorArgb: Long,
    widthFraction: Float,
    brushKey: String,
    opacity: Float,
    erasing: Boolean,
    imageWidth: Int,
    imageHeight: Int
) {
    val base = ColourUtils.fromArgbLong(colorArgb)
    val tint = if (erasing) {
        MaterialTheme.colorScheme.onSurface
    } else {
        base.copy(alpha = (base.alpha * opacity).coerceIn(0f, 1f))
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val sx = size.width / imageWidth.toFloat()
        val sy = size.height / imageHeight.toFloat()
        val strokeWidth = (widthFraction * minOf(imageWidth, imageHeight) *
            minOf(sx, sy)).coerceAtLeast(1f)

        val cap = if (DrawBrushes.optionFor(brushKey).key == "square") {
            StrokeCap.Butt
        } else {
            StrokeCap.Round
        }

        // Erase: a ring along the path, sized to the brush.
        if (erasing) {
            if (points.size == 1) {
                val p = points[0]
                drawCircle(
                    color = tint,
                    radius = strokeWidth / 2f,
                    center = Offset(p.x * imageWidth * sx, p.y * imageHeight * sy),
                    style = Stroke(width = 1.5f)
                )
                return@Canvas
            }
            val path = Path().apply {
                moveTo(points[0].x * imageWidth * sx, points[0].y * imageHeight * sy)
                for (i in 1 until points.size) {
                    lineTo(points[i].x * imageWidth * sx, points[i].y * imageHeight * sy)
                }
            }
            drawPath(
                path = path,
                color = tint,
                style = Stroke(width = strokeWidth, cap = cap, join = StrokeJoin.Round)
            )
            return@Canvas
        }

        if (points.size < 2) return@Canvas

        val path = Path().apply {
            moveTo(points[0].x * imageWidth * sx, points[0].y * imageHeight * sy)
            for (i in 1 until points.size) {
                lineTo(points[i].x * imageWidth * sx, points[i].y * imageHeight * sy)
            }
        }

        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = strokeWidth, cap = cap, join = StrokeJoin.Round)
        )
    }
}

/**
 * Minimum spacing between recorded points, in preview pixels.
 *
 * Roughly a fraction of the narrowest sensible brush. Coarser than this and a
 * curve visibly facets; finer and the saved project fills with duplicates.
 */
private const val MIN_POINT_SPACING_PX = 2.5f

private fun hypot(x: Float, y: Float): Float = sqrt(x * x + y * y)