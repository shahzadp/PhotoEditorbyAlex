package com.alex.photoeditor.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alex.photoeditor.core.ImageLayer
import com.alex.photoeditor.ui.EditorViewModel
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Direct manipulation for a stacked image.
 *
 * Gestures, as requested:
 *  - **One finger drag** moves the layer.
 *  - **Two finger pinch** scales and rotates it.
 *  - Opacity stays on the panel slider.
 *
 * Deltas are converted into the layer's normalised units against the
 * *on-screen* image size every frame, so a drag tracks the finger exactly and
 * does not depend on the preview or export resolution.
 *
 * All of it runs in a *single* `PointerInputScope` loop. An earlier version
 * attached `detectDragGestures` and `detectTransformGestures` as two sibling
 * `pointerInput` modifiers. That does not compose: both handlers are offered
 * every event, and the transform detector consumes the single-finger pan
 * during its own touch-slop phase, so `detectDragGestures` stopped being
 * called and panning was dead. One loop that dispatches on the number of
 * pressed pointers removes the race by construction.
 */
@Composable
fun LayerGestureStage(
    bitmap: android.graphics.Bitmap?,
    layer: ImageLayer?,
    vm: EditorViewModel,
    padding: Dp = 12.dp,
    modifier: Modifier = Modifier
) {
    if (bitmap == null || layer == null) {
        Box(modifier = modifier.fillMaxSize())
        return
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Fit the image exactly as the crop stage does, so the drag maths and
        // the drawn image share one coordinate space.
        val maxW = maxWidth.value - padding.value * 2f
        val maxH = maxHeight.value - padding.value * 2f
        val ar = bitmap.width.toFloat() / bitmap.height.toFloat()
        val imgWv = if (ar * maxH > maxW) maxW else maxH * ar
        val imgHv = imgWv / ar

        val id = layer.id

        // Live copies so the gesture responds immediately rather than waiting
        // for a render round trip. Keyed on the layer id so switching layers
        // resets them to that layer's stored values.
        var liveX by remember(id) { mutableFloatStateOf(layer.offsetX) }
        var liveY by remember(id) { mutableFloatStateOf(layer.offsetY) }
        var liveScale by remember(id) { mutableFloatStateOf(layer.scale) }
        var liveRot by remember(id) { mutableFloatStateOf(layer.rotationDegrees) }

        Box(
            modifier = Modifier
                .width(imgWv.dp)
                .height(imgHv.dp)
                .background(Color.Transparent)
                .pointerInput(id, imgWv, imgHv) {
                    val slop = viewConfiguration.touchSlop
                    // Guard against a zero-sized node so the first frame cannot
                    // divide by zero and poison the offsets.
                    val w = size.width.toFloat().takeIf { it > 0f } ?: 1f
                    val h = size.height.toFloat().takeIf { it > 0f } ?: 1f

                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)

                        // 0 = undecided, 1 = dragging, 2 = pinching
                        var mode = 0
                        var travelled = Offset.Zero
                        var prevCentroid = first.position
                        var prevSpread = 0f
                        var prevAngle = 0f

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            val positions = pressed.map { it.position }
                            val centroid = positions
                                .fold(Offset.Zero) { acc, p -> acc + p } /
                                positions.size.toFloat()

                            val two = positions.take(2)
                            val spread = if (two.size == 2) {
                                hypot(two[0].x - two[1].x, two[0].y - two[1].y)
                            } else 0f
                            val angle = if (two.size == 2) {
                                Math.toDegrees(
                                    atan2(two[1].y - two[0].y, two[1].x - two[0].x).toDouble()
                                ).toFloat()
                            } else 0f

                            val delta = centroid - prevCentroid

                            if (mode == 0) {
                                if (positions.size >= 2) {
                                    // Second finger down: go straight to pinch
                                    // without a slop wait, so a pinch feels
                                    // immediate.
                                    mode = 2
                                    prevSpread = spread
                                    prevAngle = angle
                                } else {
                                    travelled += delta
                                    if (travelled.getDistance() > slop) mode = 1
                                }
                                if (mode != 0) {
                                    event.changes.forEach {
                                        if (it.positionChanged()) it.consume()
                                    }
                                }
                                prevCentroid = centroid
                                continue
                            }

                            // Past slop: own the gesture so nothing underneath
                            // can steal it.
                            event.changes.forEach {
                                if (it.positionChanged()) it.consume()
                            }

                            if (mode == 2) {
                                if (positions.size < 2) {
                                    // Fingers reduced back to one. Fall back to
                                    // dragging from wherever the centroid is now
                                    // instead of freezing.
                                    mode = 1
                                    prevCentroid = centroid
                                    continue
                                }
                                if (prevSpread > 0f && spread > 0f) {
                                    liveScale =
                                        (liveScale * (spread / prevSpread)).coerceIn(0.05f, 3f)
                                    vm.setLayerScale(id, liveScale)
                                }
                                liveRot = wrapDegrees(liveRot + wrapDegrees(angle - prevAngle))
                                vm.setLayerRotation(id, liveRot)
                                prevSpread = spread
                                prevAngle = angle
                            } else {
                                // One finger: move. Normalised against the
                                // on-screen box so the layer follows the finger
                                // exactly at any preview size.
                                liveX = (liveX + delta.x / w).coerceIn(-1f, 1f)
                                liveY = (liveY + delta.y / h).coerceIn(-1f, 1f)
                                vm.setLayerOffset(id, liveX, liveY)
                            }

                            prevCentroid = centroid
                        }

                        // Gesture finished: settle the remaining transform.
                        vm.setLayerScale(id, liveScale)
                        vm.setLayerRotation(id, liveRot)
                    }
                }
                .semantics { contentDescription = "Drag to move, pinch to resize and rotate" }
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** Fold an angle into (-180, 180] so incremental rotation never unwinds. */
private fun wrapDegrees(deg: Float): Float = ((deg + 180f) % 360f + 360f) % 360f - 180f
