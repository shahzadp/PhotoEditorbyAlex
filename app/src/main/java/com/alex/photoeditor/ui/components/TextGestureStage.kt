package com.alex.photoeditor.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import com.alex.photoeditor.core.Eyedropper
import com.alex.photoeditor.core.TextLayer
import com.alex.photoeditor.ui.EditorViewModel
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Direct manipulation for a text object.
 *
 * Mirrors [LayerGestureStage] exactly — one finger moves, two fingers scale
 * and rotate — because the two must feel identical or switching between an
 * image and a caption mid-edit becomes unpredictable.
 *
 * The one deliberate difference is that a two-finger gesture *also* resizes
 * via spread, because text has no intrinsic size the user can grab; there is
 * no edge to drag. Scale is therefore the only way to change the type size,
 * which is why there is no separate size gesture here.
 */
@Composable
fun TextGestureStage(
    bitmap: android.graphics.Bitmap?,
    layer: TextLayer?,
    vm: EditorViewModel,
    padding: Dp = 12.dp,
    eyedropperActive: Boolean = false,
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
        val maxW = maxWidth.value - padding.value * 2f
        val maxH = maxHeight.value - padding.value * 2f
        val ar = bitmap.width.toFloat() / bitmap.height.toFloat()
        val imgWv = if (ar * maxH > maxW) maxW else maxH * ar
        val imgHv = imgWv / ar

        val id = layer.id

        var liveX by remember(id) { mutableFloatStateOf(layer.offsetX) }
        var liveY by remember(id) { mutableFloatStateOf(layer.offsetY) }
        var liveScale by remember(id) {
            mutableFloatStateOf(layer.sizeFraction / DEFAULT_TEXT_FRACTION)
        }
        var liveRot by remember(id) { mutableFloatStateOf(layer.rotationDegrees) }
        // Same smoother as the image layer stage, so the two objects cannot
        // drift apart in how they respond to a pinch.
        val smoother = remember(id) {
            RotationSmoother().apply { reset(layer.rotationDegrees) }
        }

        Box(
            modifier = Modifier
                .width(imgWv.dp)
                .height(imgHv.dp)
                .background(Color.Transparent)
                .pointerInput(id, imgWv, imgHv, eyedropperActive) {
                    val slop = viewConfiguration.touchSlop
                    val w = size.width.toFloat().takeIf { it > 0f } ?: 1f
                    val h = size.height.toFloat().takeIf { it > 0f } ?: 1f

                    // In eyedropper mode the stage becomes a colour sampler: a
                    // tap reads the flattened preview at that point, so it
                    // picks up whatever is actually visible there — the base
                    // photo, an added image or an existing text object.
                    if (eyedropperActive) {
                        val onPick: (Offset) -> Unit = { at ->
                            val nx = (at.x / w).coerceIn(0f, 1f)
                            val ny = (at.y / h).coerceIn(0f, 1f)
                            Eyedropper.sample(bitmap, nx, ny)?.let { vm.setTextColor(id, it) }
                        }
                        detectTapGestures(onPick)
                        return@pointerInput
                    }

                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)

                        // 0 = undecided, 1 = moving, 2 = transform
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

                            event.changes.forEach {
                                if (it.positionChanged()) it.consume()
                            }

                            if (mode == 2) {
                                if (positions.size < 2) {
                                    mode = 1
                                    prevCentroid = centroid
                                    continue
                                }
                                if (prevSpread > 0f && spread > 0f) {
                                    liveScale = (liveScale * (spread / prevSpread))
                                        .coerceIn(MIN_SCALE, MAX_SCALE)
                                }
                                // Accumulated internally and damped, same as an image
                                // layer, so scaling a caption never rotates it
                                // by accident. `onDelta` returns the absolute
                                // rotation, so this is an assignment.
                                liveRot = smoother.onDelta(angle - prevAngle)
                                // One live call for all four values. The panel's
                                // size slider still tracks the pinch because it
                                // reads the same committed state.
                                vm.setTextTransformLive(
                                    id,
                                    liveX,
                                    liveY,
                                    liveScale * DEFAULT_TEXT_FRACTION,
                                    liveRot
                                )
                                prevSpread = spread
                                prevAngle = angle
                            } else {
                                liveX = (liveX + delta.x / w).coerceIn(-1f, 1f)
                                liveY = (liveY + delta.y / h).coerceIn(-1f, 1f)
                                vm.setTextTransformLive(
                                    id,
                                    liveX,
                                    liveY,
                                    liveScale * DEFAULT_TEXT_FRACTION,
                                    liveRot
                                )
                            }

                            prevCentroid = centroid
                        }

                        vm.endTransformGesture("Move text")
                    }
                }
                .semantics {
                    contentDescription = if (eyedropperActive) {
                        "Tap the photo to pick a colour"
                    } else {
                        "Move text, pinch to resize and rotate"
                    }
                }
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

/** Matches [TextLayer]'s default size, used as the pinch scale's reference. */
private const val DEFAULT_TEXT_FRACTION = 0.06f
private const val MIN_SCALE = 0.15f
private const val MAX_SCALE = 10f
