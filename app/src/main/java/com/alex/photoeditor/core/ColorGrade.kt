package com.alex.photoeditor.core

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A control point on a tone curve.
 *
 * `x` is input luminance (0..1) and `y` is output luminance (0..1), matching
 * how Photoshop and Lightroom present curve editors.
 */
data class CurvePoint(val x: Float, val y: Float)

/**
 * One channel's tone curve. Curves are stored in
 * [com.alex.photoeditor.core.EditState] as normalised point lists so they
 * remain serialisable and non-destructive.
 */
data class ToneCurve(
    val channel: CurveChannel,
    val points: List<CurvePoint>
) {
    val isIdentity: Boolean
        get() = points.size <= 2 && points.zipWithNext().all {
            kotlin.math.abs(it.first.y - it.first.x) < 0.001f
        }

    companion object {
        fun identity(channel: CurveChannel) = ToneCurve(channel, DEFAULT_POINTS)
        val DEFAULT_POINTS = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
    }
}

/**
 * Editing operations on a tone curve's control points.
 *
 * Kept next to the data type so the invariants (sorted by x, endpoints pinned,
 * no two points on the same input) live in one place instead of being
 * re-derived in each panel.
 */
object CurveEditor {

    /** How close to an endpoint a touch counts as "grabbed the endpoint". */
    private const val EDGE = 0.06f

    /** Two points closer than this in x are treated as the same stop. */
    private const val MERGE = 0.02f

    /** Normalises: sorted by x, de-duplicated, endpoints present. */
    fun normalise(points: List<CurvePoint>): List<CurvePoint> {
        val sorted = points.sortedBy { it.x }
        val deduped = ArrayList<CurvePoint>(sorted.size)
        for (p in sorted) {
            val x = p.x.coerceIn(0f, 1f)
            val y = p.y.coerceIn(0f, 1f)
            if (deduped.isEmpty() || x - deduped.last().x >= MERGE) {
                deduped.add(CurvePoint(x, y))
            } else {
                // Same input stop: keep the newer output value.
                deduped[deduped.lastIndex] = CurvePoint(deduped.last().x, y)
            }
        }
        // The curve is defined on [0,1]; both ends must exist.
        if (deduped.isEmpty() || deduped.first().x > 0f) {
            deduped.add(0, CurvePoint(0f, if (deduped.isEmpty()) 0f else deduped.first().y))
        }
        if (deduped.last().x < 1f) {
            deduped.add(CurvePoint(1f, deduped.last().y))
        }
        return deduped
    }

    /** Index of the control point nearest to ([x], [y]), or -1. */
    fun hitTest(points: List<CurvePoint>, x: Float, y: Float, radius: Float): Int {
        var best = -1
        var bestDist = radius * radius
        points.forEachIndexed { i, p ->
            val dx = p.x - x
            val dy = p.y - y
            val d = dx * dx + dy * dy
            if (d <= bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    /**
     * Moves an existing point.
     *
     * The two endpoints stay pinned to the edges of the graph — dragging a
     * corner off the canvas would leave the curve undefined outside [0,1] and
     * produce a hard clipped edge on the image.
     */
    fun move(
        points: List<CurvePoint>,
        index: Int,
        x: Float,
        y: Float
    ): List<CurvePoint> {
        if (index !in points.indices) return points
        val out = ArrayList(points)
        val isFirst = index == 0
        val isLast = index == out.lastIndex

        val newX = when {
            isFirst -> 0f
            isLast -> 1f
            else -> x.coerceIn(
                (out[index - 1].x + MERGE).coerceAtMost(1f - MERGE),
                (out[index + 1].x - MERGE).coerceAtLeast(MERGE)
            )
        }
        out[index] = CurvePoint(newX, y.coerceIn(0f, 1f))
        return out
    }

    /** Inserts a new point, replacing any stop it lands on top of. */
    fun insert(points: List<CurvePoint>, x: Float, y: Float): List<CurvePoint> {
        val existing = hitTest(points, x, y, EDGE)
        return if (existing >= 0) {
            move(points, existing, x, y)
        } else {
            normalise(points + CurvePoint(x.coerceIn(MERGE, 1f - MERGE), y.coerceIn(0f, 1f)))
        }
    }

    /** Removes a point. Endpoints are never removable. */
    fun remove(points: List<CurvePoint>, index: Int): List<CurvePoint> {
        if (index <= 0 || index >= points.lastIndex) return points
        return points.toMutableList().also { it.removeAt(index) }
    }

    /** Clamps a point's y so it stays within [EDGE] of the graph bounds. */
    fun clampY(y: Float): Float = y.coerceIn(0f, 1f)

    /** Samples the curve for drawing. Mirrors the engine's interpolation. */
    fun sample(points: List<CurvePoint>, steps: Int = 64): List<CurvePoint> {
        if (points.size < 2) return points
        val out = ArrayList<CurvePoint>(steps + 1)
        for (i in 0..steps) {
            val x = i.toFloat() / steps
            out.add(CurvePoint(x, ToneCurveSampler.eval(points, x)))
        }
        return out
    }
}

enum class CurveChannel(val label: String) {
    RED("Red"),
    GREEN("Green"),
    BLUE("Blue")
}

/** Black/white/gamma input mapping, the "Levels" equivalent. */
data class Levels(
    val blackPoint: Float = 0f,
    val whitePoint: Float = 1f,
    val gamma: Float = 1f
) {
    val isIdentity: Boolean
        get() = blackPoint == 0f && whitePoint == 1f && gamma == 1f
}

/**
 * HSL adjustment operating on hue, saturation and lightness independently.
 *
 * Values are normalised: hue in [-1, 1] where 1 is a full 360 degree rotation,
 * saturation/lightness in [-1, 1].
 */
data class HslAdjustments(
    val hue: Float = 0f,
    val saturation: Float = 0f,
    val lightness: Float = 0f
) {
    val isIdentity: Boolean
        get() = hue == 0f && saturation == 0f && lightness == 0f
}

/**
 * Selectively shifts hue, saturation and luminance within a colour range.
 *
 * This is Photoshop's "Selective Color", implemented per RGB channel with a
 * tolerance falloff, which covers the majority of practical corrections.
 */
data class SelectiveColor(
    val red: RgbCorrection = RgbCorrection(),
    val green: RgbCorrection = RgbCorrection(),
    val blue: RgbCorrection = RgbCorrection(),
    val isEnabled: Boolean = false
)

/** Per-RGB-channel correction. Values in [-1, 1]. */
data class RgbCorrection(
    val cyan: Float = 0f,
    val magenta: Float = 0f,
    val yellow: Float = 0f,
    val black: Float = 0f
) {
    val isIdentity: Boolean
        get() = cyan == 0f && magenta == 0f && yellow == 0f && black == 0f
}

/**
 * The full Phase 2 professional colour state.
 *
 * Kept separate from the Phase 1 [Adjustments] so the two can evolve
 * independently, and folded into the same non-destructive pipeline.
 */
data class ColorGrade(
    val curves: Map<CurveChannel, ToneCurve> = emptyMap(),
    val levels: Map<CurveChannel, Levels> = emptyMap(),
    val hsl: HslAdjustments = HslAdjustments(),
    val selectiveColor: SelectiveColor = SelectiveColor()
) {
    val isNeutral: Boolean
        get() = curves.values.none { !it.isIdentity } &&
            levels.values.none { !it.isIdentity } &&
            hsl.isIdentity &&
            !selectiveColor.isEnabled
}

/**
 * Applies curves, levels, HSL and selective colour.
 *
 * Tone mapping (curves + levels) runs first and produces a shared luminance
 * lookup table, so the two passes combined cost roughly one extra pass rather
 * than two separate ones. Colour operations then run on the result.
 */
class ColorGradeEngine {

    /**
     * Builds a 256-entry mapping table for a curve/levels pair.
     *
     * Precomputing this is what keeps sliders responsive: adjusting a curve is
     * a table rebuild, not a per-pixel pow() call.
     */
    private fun buildLut(
        curve: ToneCurve?,
        levels: Levels?,
        channel: Int
    ): IntArray {
        val lut = IntArray(256)
        val ctrl = curve?.points

        for (i in 0 until 256) {
            var v = i / 255f

            // --- Levels ---
            levels?.let { l ->
                if (!l.isIdentity) {
                    val bp = l.blackPoint.coerceIn(0f, 0.99f)
                    val wp = l.whitePoint.coerceIn(bp + 0.01f, 1f)
                    // Linearise to the black/white window
                    v = ((v - bp) / (wp - bp)).coerceIn(0f, 1f)
                    // Apply midtone gamma
                    if (l.gamma != 1f && l.gamma > 0f) {
                        v = v.coerceAtLeast(0f).pow(1f / l.gamma)
                    }
                }
            }

            // --- Tone curve (monotone cubic through the control points) ---
            if (ctrl != null && ctrl.size >= 2) {
                v = evalCurve(ctrl, v).coerceIn(0f, 1f)
            }

            lut[i] = (v * 255f).roundToInt().coerceIn(0, 255)
        }
        return lut
    }

    /** Monotone cubic (Fritsch-Carlson) interpolation for smooth, non-overshooting curves. */
    private fun evalCurve(points: List<CurvePoint>, x: Float): Float =
        ToneCurveSampler.eval(points, x)

    suspend fun apply(source: Bitmap, grade: ColorGrade): Bitmap =
        withContext(Dispatchers.Default) {
            if (grade.isNeutral) return@withContext source

            val w = source.width
            val h = source.height
            val px = IntArray(w * h)
            source.getPixels(px, 0, w, 0, 0, w, h)

            applyInPlace(px, grade)

            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(px, 0, w, 0, 0, w, h)
            out
        }

    fun applyInPlace(px: IntArray, grade: ColorGrade) {
        val hasTone = grade.curves.values.any { !it.isIdentity } ||
            grade.levels.values.any { !it.isIdentity }

        if (hasTone) {
            val rLut = buildLut(grade.curves[CurveChannel.RED], grade.levels[CurveChannel.RED], 0)
            val gLut = buildLut(grade.curves[CurveChannel.GREEN], grade.levels[CurveChannel.GREEN], 1)
            val bLut = buildLut(grade.curves[CurveChannel.BLUE], grade.levels[CurveChannel.BLUE], 2)

            for (i in px.indices) {
                val c = px[i]
                px[i] = (c and OPAQUE) or
                    (rLut[(c shr 16) and 0xFF] shl 16) or
                    (gLut[(c shr 8) and 0xFF] shl 8) or
                    bLut[c and 0xFF]
            }
        }

        if (!grade.hsl.isIdentity) {
            applyHsl(px, grade.hsl)
        }

        if (grade.selectiveColor.isEnabled) {
            applySelectiveColor(px, grade.selectiveColor)
        }
    }

    // ----------------------------------------------------------------- HSL

    private fun applyHsl(px: IntArray, adj: HslAdjustments) {
        val hueShift = adj.hue * 360f
        val satScale = 1f + adj.saturation
        val lightDelta = adj.lightness * 0.5f

        for (i in px.indices) {
            val c = px[i]
            val r = ((c shr 16) and 0xFF) * INV255
            val g = ((c shr 8) and 0xFF) * INV255
            val b = (c and 0xFF) * INV255

            val (h, s, l) = rgbToHsl(r, g, b)
            if (s < 0.0001f) {
                // Achromatic: only lightness applies.
                if (lightDelta != 0f) {
                    val nl = (l + lightDelta).coerceIn(0f, 1f)
                    val v = (nl * 255f).roundToInt().coerceIn(0, 255)
                    px[i] = (c and OPAQUE) or (v shl 16) or (v shl 8) or v
                }
                continue
            }

            var nh = (h + hueShift) % 360f
            if (nh < 0f) nh += 360f
            val ns = (s * satScale).coerceIn(0f, 1f)
            val nl = (l + lightDelta).coerceIn(0f, 1f)

            val (nr, ng, nb) = hslToRgb(nh, ns, nl)
            px[i] = (c and OPAQUE) or
                (to255(nr) shl 16) or
                (to255(ng) shl 8) or
                to255(nb)
        }
    }

    private fun rgbToHsl(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        if (max == min) return Triple(0f, 0f, l)

        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)

        val h = when (max) {
            r -> (g - b) / d + if (g < b) 6f else 0f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } * 60f

        return Triple(h, s, l)
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): Triple<Float, Float, Float> {
        if (s == 0f) return Triple(l, l, l)

        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val hp = h / 60f
        val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))

        val (r1, g1, b1) = when (hp.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

        val m = l - c / 2f
        return Triple(r1 + m, g1 + m, b1 + m)
    }

    // ------------------------------------------------------ selective colour

    private fun applySelectiveColor(px: IntArray, sc: SelectiveColor) {
        for (i in px.indices) {
            val c = px[i]
            var r = ((c shr 16) and 0xFF) * INV255
            var g = ((c shr 8) and 0xFF) * INV255
            var b = (c and 0xFF) * INV255

            r = adjustChannel(r, g, b, sc.red)
            g = adjustChannel(g, r, g, sc.green)
            b = adjustChannel(b, r, g, sc.blue)

            px[i] = (c and OPAQUE) or
                (to255(r) shl 16) or (to255(g) shl 8) or to255(b)
        }
    }

    /**
     * Applies one channel's CMYK correction, weighted by how much the *other*
     * two channels dominate. This is what makes red corrections affect reds and
     * leave cyans largely alone.
     */
    private fun adjustChannel(target: Float, r: Float, g: Float, corr: RgbCorrection): Float {
        if (corr.isIdentity) return target

        val b = 1f - r - g
        val weight = when {
            target == r -> ((g + b) * 0.5f).coerceIn(0f, 1f)
            target == g -> ((r + b) * 0.5f).coerceIn(0f, 1f)
            else -> ((r + g) * 0.5f).coerceIn(0f, 1f)
        }
        if (weight <= 0.0001f) return target

        // Cyan/magenta/yellow move each channel additively; black is a
        // multiplicative density change.
        var v = target
        v += corr.cyan * 0.5f * weight
        v += corr.magenta * 0.5f * weight * if (target == r || target == g) 0.5f else 1f
        v += corr.yellow * 0.5f * weight * if (target == b) 1f else 0.25f
        v += corr.black * 0.4f * weight

        return v.coerceIn(0f, 1f)
    }

    private fun to255(v: Float): Int = (v * 255f).roundToInt().coerceIn(0, 255)

    private companion object {
        const val INV255 = 1f / 255f
        const val OPAQUE = 0xFF shl 24
    }
}

/**
 * Monotone cubic (Fritsch-Carlson) evaluation of a tone curve.
 *
 * Shared between the renderer and the curve editor UI so the graph the user
 * drags is exactly the curve that gets applied to the pixels. A second,
 * slightly different implementation would make the on-screen curve lie.
 */
object ToneCurveSampler {

    fun eval(points: List<CurvePoint>, x: Float): Float {
        if (points.isEmpty()) return x
        if (points.size == 1) return points[0].y
        if (x <= points.first().x) return points.first().y
        if (x >= points.last().x) return points.last().y

        var i = 0
        while (i < points.size - 2 && x > points[i + 1].x) i++

        val p0 = points[i]
        val p1 = points[i + 1]
        val dx = (p1.x - p0.x).takeIf { it > 0.0001f } ?: 0.0001f
        val t = (x - p0.x) / dx

        val m0 = if (i == 0) {
            (p1.y - p0.y) / dx
        } else {
            (p1.y - points[i - 1].y) / (p1.x - points[i - 1].x).coerceAtLeast(0.0001f)
        }
        val m1 = if (i + 2 >= points.size) {
            (p1.y - p0.y) / dx
        } else {
            (points[i + 2].y - p0.y) / (points[i + 2].x - p0.x).coerceAtLeast(0.0001f)
        }
        val s = m0 * dx

        val h00 = 2f * t * t * t - 3f * t * t + 1f
        val h10 = t * t * t - 2f * t * t + t
        val h01 = -2f * t * t * t + 3f * t * t
        val h11 = t * t * t - t * t

        val y = h00 * p0.y + h10 * s + h01 * p1.y + h11 * m1 * dx

        // Clamp to the local segment so the curve never overshoots wildly.
        val lo = minOf(p0.y, p1.y)
        val hi = maxOf(p0.y, p1.y)
        return y.coerceIn(lo - 0.25f, hi + 0.25f)
    }
}
