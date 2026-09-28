package com.alex.photoeditor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Interactive crop frame drawn over the preview.
 *
 * Stored in *fractional* coordinates (0..1) so it stays correct regardless of
 * how the preview is scaled or letterboxed on screen. The caller converts to a
 * pixel [com.alex.photoeditor.core.CropRect] in image space.
 */
data class CropSelection(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /**
     * Forces the rect inside 0..1 with a non-degenerate size.
     *
     * Size is *preserved* and only the position is corrected. An earlier
     * version returned the full frame whenever the span dropped below the
     * minimum, so shrinking a crop past that point made the frame jump to full
     * size mid-drag and then collapse unpredictably.
     */
    fun clamped(): CropSelection {
        if (!left.isFinite() || !top.isFinite() ||
            !right.isFinite() || !bottom.isFinite()
        ) {
            return FULL
        }

        val l = min(left, right)
        val r = max(left, right)
        val t = min(top, bottom)
        val b = max(top, bottom)

        val (fx, fw) = fitSpan(l, r, MIN_SIZE)
        val (fy, fh) = fitSpan(t, b, MIN_SIZE)
        return CropSelection(fx, fy, fx + fw, fy + fh)
    }

    /** Adjusts a span to at least [minSpan] and at most 1, then slides it into 0..1. */
    private fun fitSpan(lo: Float, hi: Float, minSpan: Float): Pair<Float, Float> {
        var span = (hi - lo).coerceIn(minSpan, 1f)
        var start = lo.coerceIn(0f, 1f - span)
        if (start + span > 1f) start = 1f - span
        return start to span
    }

    val isFull: Boolean
        get() = left <= 0.001f && top <= 0.001f &&
            right >= 0.999f && bottom >= 0.999f

    companion object {
        val FULL = CropSelection(0f, 0f, 1f, 1f)
    }
}

/** Which part of the frame the finger grabbed, or the interior for panning. */
private enum class DragTarget {
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
    TOP, BOTTOM, LEFT, RIGHT, INTERIOR
}

/**
 * Draws a crop overlay and handles dragging.
 *
 * Interaction rules that make this feel right:
 *  - The grabbed handle is resolved **once** at gesture start and latched for
 *    the whole drag. Re-testing every frame loses the handle the moment it
 *    travels away from the finger, which is what made the frame stick or jump
 *    past the image border.
 *  - Dragging the interior pans the selection, so a small crop can still be
 *    repositioned anywhere over a large photo.
 *  - Results are always clamped into 0..1, so the frame can never grow beyond
 *    the image bounds.
 *  - Double-tap resets to the full frame.
 *
 * [onChange] fires continuously; [onCommit] fires on release so the caller can
 * push a single undo entry per gesture.
 */
@Composable
fun CropOverlay(
    selection: CropSelection,
    onChange: (CropSelection) -> Unit,
    onCommit: (CropSelection) -> Unit,
    onDragStateChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val d = density.density
    val cornerTouchPx = CORNER_TOUCH_DP * d
    val edgeTouchPx = EDGE_TOUCH_DP * d
    val cornerDrawPx = 14f * d
    val edgeDrawPx = 8f * d

    var grabbed by remember { mutableStateOf<DragTarget?>(null) }
    var startSel by remember { mutableStateOf<CropSelection?>(null) }
    var grabOffset by remember { mutableStateOf(Offset.Zero) }
    // Where inside the frame the pan gesture grabbed it, as a fraction.
    var grabAnchor by remember { mutableStateOf(Offset.Zero) }
    // Live selection owned by the gesture. Because pointerInput is keyed on
    // Unit it never re-reads the `selection` parameter, so routing every drag
    // through the ViewModel (and back again a frame later) made the frame lag
    // behind the finger. Driving the gesture locally keeps it 1:1.
    var live by remember(selection) { mutableStateOf(selection) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = "Crop area" }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val target = hitTest(
                                live, pos, size.width, size.height,
                                cornerTouchPx, edgeTouchPx
                            )
                            if (target != null) onDragStateChange(true)
                            grabbed = target
                            startSel = live
                            // For a pan, remember where inside the frame the
                            // finger landed so the frame tracks it exactly.
                            if (target == DragTarget.INTERIOR && size.width > 0 && size.height > 0) {
                                grabAnchor = Offset(
                                    ((pos.x / size.width) - live.left)
                                        .coerceIn(0f, live.width),
                                    ((pos.y / size.height) - live.top)
                                        .coerceIn(0f, live.height)
                                )
                            } else {
                                grabAnchor = Offset.Zero
                            }
                            // Keep the finger's position within the handle so
                            // it does not snap to centre on the first move.
                            grabOffset = if (target == null || target == DragTarget.INTERIOR) {
                                Offset.Zero
                            } else {
                                handlePoint(target, live, size.width, size.height) - pos
                            }
                        },
                        onDragEnd = {
                            val done = live
                            grabbed = null
                            startSel = null
                            onDragStateChange(false)
                            onCommit(done)
                        },
                        onDragCancel = {
                            val done = live
                            grabbed = null
                            startSel = null
                            onDragStateChange(false)
                            onCommit(done)
                        }
                    ) { change, _ ->
                        val target = grabbed
                        val start = startSel
                        if (target != null && start != null) {
                            change.consume()
                            val next = applyDrag(
                                target = target,
                                start = start,
                                pointer = change.position - grabOffset,
                                w = size.width,
                                h = size.height,
                                grabAnchorX = grabAnchor.x,
                                grabAnchorY = grabAnchor.y
                            )
                            live = next
                            onChange(next)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            live = CropSelection.FULL
                            onChange(CropSelection.FULL)
                            onCommit(CropSelection.FULL)
                        }
                    )
                }        ) {
            drawCropChrome(live, cornerPx = cornerDrawPx, edgePx = edgeDrawPx)
        }
    }
}

/** Pixel position of a handle for the current selection. */
private fun handlePoint(
    target: DragTarget,
    sel: CropSelection,
    w: Int,
    h: Int
): Offset = Offset(
    when (target) {
        DragTarget.TOP_LEFT, DragTarget.LEFT, DragTarget.BOTTOM_LEFT -> sel.left * w
        DragTarget.TOP_RIGHT, DragTarget.RIGHT, DragTarget.BOTTOM_RIGHT -> sel.right * w
        else -> (sel.left + sel.right) / 2f * w
    },
    when (target) {
        DragTarget.TOP_LEFT, DragTarget.TOP, DragTarget.TOP_RIGHT -> sel.top * h
        DragTarget.BOTTOM_LEFT, DragTarget.BOTTOM, DragTarget.BOTTOM_RIGHT -> sel.bottom * h
        else -> (sel.top + sel.bottom) / 2f * h
    }
)

/**
 * Decides what the user grabbed.
 *
 * Corners win over edges, edges over the interior. Returns [DragTarget.INTERIOR]
 * when the touch lands inside the selection so the frame can be panned, and
 * `null` outside the frame (which ignores the gesture).
 */
private fun hitTest(
    sel: CropSelection,
    pos: Offset,
    w: Int,
    h: Int,
    cornerPx: Float,
    edgePx: Float
): DragTarget? {
    if (w <= 0 || h <= 0) return null

    val l = sel.left * w
    val t = sel.top * h
    val r = sel.right * w
    val b = sel.bottom * h
    val x = pos.x
    val y = pos.y

    val nearL = abs(x - l) <= cornerPx
    val nearR = abs(x - r) <= cornerPx
    val nearT = abs(y - t) <= cornerPx
    val nearB = abs(y - b) <= cornerPx
    val withinX = x >= l && x <= r
    val withinY = y >= t && y <= b

    return when {
        nearL && nearT -> DragTarget.TOP_LEFT
        nearR && nearT -> DragTarget.TOP_RIGHT
        nearL && nearB -> DragTarget.BOTTOM_LEFT
        nearR && nearB -> DragTarget.BOTTOM_RIGHT

        abs(x - l) <= edgePx && withinY -> DragTarget.LEFT
        abs(x - r) <= edgePx && withinY -> DragTarget.RIGHT
        abs(y - t) <= edgePx && withinX -> DragTarget.TOP
        abs(y - b) <= edgePx && withinX -> DragTarget.BOTTOM

        withinX && withinY -> DragTarget.INTERIOR
        else -> null
    }
}

/**
 * Applies a drag relative to the gesture's starting selection.
 *
 * Every result is clamped so the selection stays within 0..1 and never smaller
 * than [MIN_SIZE]. Panning is additionally bounded so the frame cannot leave
 * the image.
 */
private fun applyDrag(
    target: DragTarget,
    start: CropSelection,
    pointer: Offset,
    w: Int,
    h: Int,
    grabAnchorX: Float = 0f,
    grabAnchorY: Float = 0f
): CropSelection {
    if (w <= 0 || h <= 0) return start

    val nx = (pointer.x / w).coerceIn(0f, 1f)
    val ny = (pointer.y / h).coerceIn(0f, 1f)

    val result = when (target) {
        DragTarget.TOP_LEFT -> CropSelection(nx, ny, start.right, start.bottom)
        DragTarget.TOP_RIGHT -> CropSelection(start.left, ny, nx, start.bottom)
        DragTarget.BOTTOM_LEFT -> CropSelection(nx, start.top, start.right, ny)
        DragTarget.BOTTOM_RIGHT -> CropSelection(start.left, start.top, nx, ny)

        DragTarget.LEFT -> CropSelection(nx, start.top, start.right, start.bottom)
        DragTarget.RIGHT -> CropSelection(start.left, start.top, nx, start.bottom)
        DragTarget.TOP -> CropSelection(start.left, ny, start.right, start.bottom)
        DragTarget.BOTTOM -> CropSelection(start.left, start.top, start.right, ny)

        DragTarget.INTERIOR -> {
            // Pan by the *delta* of the finger since the gesture started.
            // Anchoring the frame's left edge to the pointer (rather than
            // translating) made the frame snap and shrink under the finger.
            val dx = nx - (start.left + grabAnchorX)
            val dy = ny - (start.top + grabAnchorY)

            // Clamp the translation so the frame stays fully on the image.
            val minDx = -start.left
            val maxDx = 1f - start.right
            val minDy = -start.top
            val maxDy = 1f - start.bottom

            val tx = dx.coerceIn(minOf(minDx, maxDx), maxOf(minDx, maxDx))
            val ty = dy.coerceIn(minOf(minDy, maxDy), maxOf(minDy, maxDy))

            CropSelection(
                start.left + tx,
                start.top + ty,
                start.left + tx + start.width,
                start.top + ty + start.height
            )
        }
    }
    return result.clamped()
}

/** Renders the dimmed surround, rule-of-thirds guides and corner handles. */
private fun DrawScope.drawCropChrome(
    sel: CropSelection,
    cornerPx: Float,
    edgePx: Float
) {
    val w = size.width
    val h = size.height
    val l = sel.left * w
    val t = sel.top * h
    val r = sel.right * w
    val b = sel.bottom * h

    // Dim everything outside the selection using an even-odd hole path.
    val outside = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(Offset.Zero, Size(w, h)))
        addRect(Rect(Offset(l, t), Size(r - l, b - t)))
    }
    drawPath(outside, Color.Black.copy(alpha = 0.55f))

    drawRect(
        color = Color.White,
        topLeft = Offset(l, t),
        size = Size(r - l, b - t),
        style = Stroke(width = 2f)
    )

    // Rule-of-thirds guides.
    val guide = Color.White.copy(alpha = 0.35f)
    for (i in 1..2) {
        val gx = l + (r - l) * i / 3f
        val gy = t + (b - t) * i / 3f
        drawLine(guide, Offset(gx, t), Offset(gx, b), strokeWidth = 1f)
        drawLine(guide, Offset(l, gy), Offset(r, gy), strokeWidth = 1f)
    }

    // Corner L-handles.
    val white = Color.White
    fun corner(x: Float, y: Float, dx: Float, dy: Float) {
        drawLine(white, Offset(x, y), Offset(x + dx * cornerPx, y), strokeWidth = 4f)
        drawLine(white, Offset(x, y), Offset(x, y + dy * cornerPx), strokeWidth = 4f)
    }
    corner(l, t, 1f, 1f)
    corner(r, t, -1f, 1f)
    corner(l, b, 1f, -1f)
    corner(r, b, -1f, -1f)

    // Edge midpoint grips.
    val midX = (l + r) / 2f
    val midY = (t + b) / 2f
    val grey = Color.White.copy(alpha = 0.85f)
    drawLine(grey, Offset(l, midY), Offset(l + edgePx, midY), strokeWidth = 3f)
    drawLine(grey, Offset(r, midY), Offset(r - edgePx, midY), strokeWidth = 3f)
    drawLine(grey, Offset(midX, t), Offset(midX, t + edgePx), strokeWidth = 3f)
    drawLine(grey, Offset(midX, b), Offset(midX, b - edgePx), strokeWidth = 3f)
}

private const val CORNER_TOUCH_DP = 32f
private const val EDGE_TOUCH_DP = 24f
private const val MIN_SIZE = 0.06f
