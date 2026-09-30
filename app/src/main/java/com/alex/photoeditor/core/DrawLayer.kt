package com.alex.photoeditor.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One point along a drawn stroke, in normalised image space.
 *
 * Normalised (0..1) rather than pixels so a stroke stays in proportion when
 * the render resolution differs between the preview and the export, and so the
 * serialised state stays small — a 40-point stroke is 80 floats instead of a
 * bitmap or a run-length blob.
 */
@Serializable
data class StrokePoint(
    val x: Float,
    val y: Float
)

/**
 * A single freehand stroke.
 *
 * Held as a polyline and re-rasterised on every render rather than baked into
 * a bitmap. That is what lets the preview and the export differ in resolution
 * without the drawing resampling twice, and it is what lets a stroke's colour
 * or width be changed after the fact.
 */
@Serializable
data class DrawStroke(
    val id: String,
    val points: List<StrokePoint> = emptyList(),
    /** 0xAARRGGBB. */
    val color: Long = 0xFF000000L,
    /** Brush width as a fraction of the image's shorter edge. */
    val widthFraction: Float = 0.012f,
    val brushKey: String = DrawBrushes.DEFAULT_KEY,
    /**
     * True for an eraser stroke.
     *
     * Erasing is a stroke rather than a mode so the eraser can be used
     * selectively — one swipe removes one mark and leaves the rest alone.
     */
    val erase: Boolean = false,
    /** 0..1, applied to [color] at draw time. */
    val opacity: Float = 1f,
    val visible: Boolean = true
) {
    val isEmpty: Boolean get() = points.size < 2
}

/**
 * A flood-filled region.
 *
 * Stored as a seed point plus a tolerance rather than as a mask: the region is
 * re-derived from the image on every render, so it survives a crop, a resize
 * or a rotation and costs a handful of bytes in the project file.
 */
@Serializable
data class DrawFill(
    val id: String,
    /** Normalised seed. */
    val x: Float,
    val y: Float,
    /** 0xAARRGGBB */
    val color: Long = 0xFF000000L,
    /** 0..1, how far a pixel may differ from the seed and still be filled. */
    val tolerance: Float = 0.12f,
    val opacity: Float = 1f,
    val visible: Boolean = true
)

/**
 * The brush styles offered.
 *
 * Each is a [Paint] configuration rather than a different algorithm, so they
 * all cost the same to draw and share one code path. "Spray" is the exception:
 * it stamps dots instead of stroking a path, because that is the only way to
 * get the broken texture people expect from an airbrush.
 */
object DrawBrushes {

    const val DEFAULT_KEY = "round"

    data class Option(val key: String, val label: String)

    val options: List<Option> = listOf(
        Option("round", "Round"),
        Option("square", "Square"),
        Option("dashed", "Dashed"),
        Option("dotted", "Dotted"),
        Option("spray", "Spray")
    )

    fun optionFor(key: String): Option =
        options.firstOrNull { it.key == key } ?: options.first()

    /**
     * Applies the brush's cap, join and dash to [paint].
     *
     * Dash lengths are expressed as multiples of the stroke width, so a dashed
     * brush stays visually consistent at every brush size.
     */
    fun apply(paint: Paint, key: String, widthPx: Float) {
        val w = max(widthPx, 1f)
        when (key) {
            "square" -> {
                paint.strokeCap = Paint.Cap.BUTT
                paint.pathEffect = null
            }
            "dashed" -> {
                paint.strokeCap = Paint.Cap.BUTT
                paint.pathEffect = DashPathEffect(floatArrayOf(w * 2.4f, w * 1.4f), 0f)
            }
            "dotted" -> {
                paint.strokeCap = Paint.Cap.ROUND
                paint.pathEffect = DashPathEffect(floatArrayOf(0.01f, w * 1.6f), 0f)
            }
            "spray" -> {
                paint.strokeCap = Paint.Cap.ROUND
                paint.pathEffect = null
            }
            else -> {
                paint.strokeCap = Paint.Cap.ROUND
                paint.pathEffect = null
            }
        }
    }
}

/**
 * Rasterises fills and strokes onto a finished render.
 *
 * Everything is drawn into one transparent layer first and that layer is then
 * composited once. The isolation is not an optimisation, it is what makes the
 * eraser safe: an eraser stroke uses [PorterDuff.Mode.CLEAR], which on a layer
 * that already held the photo would delete the photograph. On an isolated
 * layer it can only remove ink that this tool put down.
 */
object DrawComposer {

    private const val MIN_BRUSH_FRACTION = 0.0015f
    private const val MAX_BRUSH_FRACTION = 0.5f

    fun compose(
        base: Bitmap,
        strokes: List<DrawStroke>,
        fills: List<DrawFill>
    ): Bitmap {
        val activeStrokes = strokes.filter { it.visible && !it.isEmpty && it.opacity > 0f }
        val activeFills = fills.filter { it.visible && it.opacity > 0f }
        if (activeStrokes.isEmpty() && activeFills.isEmpty()) return base

        val out = base.copy(Bitmap.Config.ARGB_8888, true) ?: return base

        // Fills run first so strokes land on top of them, matching how the
        // panel presents them: pick a fill, then draw over it.
        if (activeFills.isNotEmpty()) {
            for (fill in activeFills) {
                floodFill(out, fill)
            }
        }

        if (activeStrokes.isEmpty()) return out

        val layer = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888)
        drawStrokes(Canvas(layer), activeStrokes, out.width, out.height)
        Canvas(out).drawBitmap(layer, 0f, 0f, null)
        return out
    }

    /** Brush width in pixels for a stroke on a [w] x [h] image. */
    private fun widthPx(stroke: DrawStroke, w: Int, h: Int): Float {
        val shortEdge = minOf(w, h).toFloat()
        val fraction = stroke.widthFraction.coerceIn(MIN_BRUSH_FRACTION, MAX_BRUSH_FRACTION)
        return (fraction * shortEdge).coerceAtLeast(1f)
    }

    private fun drawStrokes(
        canvas: Canvas,
        strokes: List<DrawStroke>,
        w: Int,
        h: Int
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            isDither = true
        }
        val path = Path()
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        for (stroke in strokes) {
            val width = widthPx(stroke, w, h)
            val alpha = (stroke.opacity.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)

            // A single dot: a tap with no drag still has to leave a mark.
            if (stroke.points.size == 1) {
                val p = stroke.points.first()
                dotPaint.color = Color.argb(
                    alpha,
                    Color.red(stroke.color.toInt()),
                    Color.green(stroke.color.toInt()),
                    Color.blue(stroke.color.toInt())
                )
                dotPaint.xfermode = if (stroke.erase) {
                    PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                } else {
                    null
                }
                canvas.drawCircle(p.x * w, p.y * h, width / 2f, dotPaint)
                continue
            }

            path.reset()
            path.moveTo(stroke.points[0].x * w, stroke.points[0].y * h)
            for (i in 1 until stroke.points.size) {
                path.lineTo(stroke.points[i].x * w, stroke.points[i].y * h)
            }

            val rgb = stroke.color.toInt() and 0x00FFFFFF
            paint.color = Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
            paint.strokeWidth = width
            paint.xfermode = if (stroke.erase) {
                PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            } else {
                null
            }

            if (DrawBrushes.optionFor(stroke.brushKey).key == "spray") {
                sprayAlong(canvas, stroke, width, alpha, dotPaint, w, h)
            } else {
                DrawBrushes.apply(paint, stroke.brushKey, width)
                canvas.drawPath(path, paint)
            }
        }
    }

    /**
     * Stamps dots along a stroke's polyline to imitate an airbrush.
     *
     * Walks the stored points directly rather than measuring the path. The
     * points are already the polyline, so re-measuring them would be work for
     * no information.
     *
     * The jitter is derived from the dot index rather than from a random
     * source, so the same stroke renders identically every frame. Real random
     * jitter would make the spray shimmer and crawl while the user was still
     * dragging it.
     */
    private fun sprayAlong(
        canvas: Canvas,
        stroke: DrawStroke,
        width: Float,
        alpha: Int,
        dotPaint: Paint,
        w: Int,
        h: Int
    ) {
        val rgb = stroke.color.toInt() and 0x00FFFFFF
        dotPaint.color = Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
        dotPaint.xfermode = if (stroke.erase) {
            PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        } else {
            null
        }

        val step = (width * 0.34f).coerceAtLeast(1f)
        val radius = width * 0.11f
        val pts = stroke.points

        // Position along the polyline, in pixels. `carry` holds the distance
        // left over between segments so spacing stays even across the seam
        // where two segments meet.
        var carry = 0f
        var index = 0

        for (i in 0 until pts.size - 1) {
            val ax = pts[i].x * w
            val ay = pts[i].y * h
            val bx = pts[i + 1].x * w
            val by = pts[i + 1].y * h
            val segLen = hypot(bx - ax, by - ay)
            if (segLen <= 0.0001f) continue

            var d = carry
            while (d <= segLen) {
                val t = d / segLen
                val cx = ax + (bx - ax) * t
                val cy = ay + (by - ay) * t
                // Two sine waves at unrelated frequencies, so the dots scatter
                // without needing a Random instance.
                val a = index * 0.7f
                val jx = kotlin.math.sin(a * 2.3f) * width * 0.42f
                val jy = kotlin.math.cos(a * 3.1f) * width * 0.42f
                canvas.drawCircle(cx + jx, cy + jy, radius, dotPaint)
                d += step
                index++
            }
            carry = d - segLen
        }
    }

    /**
     * Scanline flood fill from a normalised seed.
     *
     * Operates on the bitmap in place. Tolerance is measured as the largest
     * per-channel difference from the seed colour, which is predictable and
     * cheap; a perceptual distance would be more correct but is far slower and
     * the difference is not visible at this tolerance range.
     */
    private fun floodFill(target: Bitmap, fill: DrawFill) {
        val w = target.width
        val h = target.height
        if (w <= 0 || h <= 0) return

        val sx = (fill.x.coerceIn(0f, 1f) * (w - 1)).roundToInt()
        val sy = (fill.y.coerceIn(0f, 1f) * (h - 1)).roundToInt()

        val px = IntArray(w * h)
        target.getPixels(px, 0, w, 0, 0, w, h)

        val seed = px[sy * w + sx]
        val sr = Color.red(seed)
        val sg = Color.green(seed)
        val sb = Color.blue(seed)
        val tol = (fill.tolerance.coerceIn(0f, 1f) * 255f).toInt()

        val rgb = fill.color.toInt() and 0x00FFFFFF
        val alpha = (fill.opacity.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        // The fill replaces the region but keeps whatever transparency the
        // region already had, so filling over a cut-out hole does not paint
        // the sky back in.
        val newColor = (alpha shl 24) or rgb

        val visited = BooleanArray(w * h)
        val stack = ArrayDeque<Int>()
        stack.addLast(sy * w + sx)

        while (stack.isNotEmpty()) {
            val start = stack.removeLast()
            val row = start / w
            var left = start % w

            // Walk left to the start of the run, then right to its end, so a
            // whole span is filled at once rather than one pixel at a time.
            while (left > 0 && !visited[row * w + left - 1] &&
                matches(px[row * w + left - 1], sr, sg, sb, tol)
            ) {
                left--
            }
            var right = left
            while (right < w - 1 && !visited[row * w + right + 1] &&
                matches(px[row * w + right + 1], sr, sg, sb, tol)
            ) {
                right++
            }

            for (i in left..right) {
                val idx = row * w + i
                visited[idx] = true
                // Multiply into whatever transparency the region already had,
                // so filling over a cut-out hole does not paint the sky back in.
                val existingAlpha = px[idx] ushr 24
                px[idx] = (((existingAlpha * alpha) / 255) shl 24) or rgb
            }

            // Queue the rows above and below over this span; they are
            // span-matched when they are popped, so the walk above stops
            // naturally at a colour change instead of overrunning it.
            if (row > 0) {
                for (i in left..right) {
                    val nidx = (row - 1) * w + i
                    if (!visited[nidx] && matches(px[nidx], sr, sg, sb, tol)) stack.addLast(nidx)
                }
            }
            if (row < h - 1) {
                for (i in left..right) {
                    val nidx = (row + 1) * w + i
                    if (!visited[nidx] && matches(px[nidx], sr, sg, sb, tol)) stack.addLast(nidx)
                }
            }
        }

        target.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun matches(candidate: Int, r: Int, g: Int, b: Int, tol: Int): Boolean {
        if (tol <= 0) return false
        return abs(Color.red(candidate) - r) <= tol &&
            abs(Color.green(candidate) - g) <= tol &&
            abs(Color.blue(candidate) - b) <= tol
    }
}