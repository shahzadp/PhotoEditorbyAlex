package com.alex.photoeditor.core

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Immutable value object holding every Phase 1 adjustment.
 *
 * All ranges are normalised: [-1f, 1f] for bipolar controls, [0f, 1f] for
 * unipolar ones, and [0f, 4f] for exposure (stops).
 */
data class Adjustments(
    val exposure: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
    val sharpen: Float = 0f,
    val denoise: Float = 0f
) {
    val isNeutral: Boolean get() = this == NEUTRAL

    companion object {
        val NEUTRAL = Adjustments()
    }
}

/** Which family of control a slider belongs to; drives UI labelling. */
enum class AdjustmentKind(
    val label: String,
    val min: Float,
    val max: Float,
    val defaultValue: Float
) {
    EXPOSURE("Exposure", -2f, 2f, 0f),
    BRIGHTNESS("Brightness", -1f, 1f, 0f),
    CONTRAST("Contrast", -1f, 1f, 0f),
    HIGHLIGHTS("Highlights", -1f, 1f, 0f),
    SHADOWS("Shadows", -1f, 1f, 0f),
    WHITES("Whites", -1f, 1f, 0f),
    BLACKS("Blacks", -1f, 1f, 0f),
    SATURATION("Saturation", -1f, 1f, 0f),
    VIBRANCE("Vibrance", -1f, 1f, 0f),
    TEMPERATURE("Temperature", -1f, 1f, 0f),
    TINT("Tint", -1f, 1f, 0f),
    CLARITY("Clarity", -1f, 1f, 0f),
    DEHAZE("Dehaze", -1f, 1f, 0f),
    SHARPEN("Sharpen", 0f, 1f, 0f),
    DENOISE("Denoise", 0f, 1f, 0f);

    fun valueIn(a: Adjustments): Float = when (this) {
        EXPOSURE -> a.exposure
        BRIGHTNESS -> a.brightness
        CONTRAST -> a.contrast
        HIGHLIGHTS -> a.highlights
        SHADOWS -> a.shadows
        WHITES -> a.whites
        BLACKS -> a.blacks
        SATURATION -> a.saturation
        VIBRANCE -> a.vibrance
        TEMPERATURE -> a.temperature
        TINT -> a.tint
        CLARITY -> a.clarity
        DEHAZE -> a.dehaze
        SHARPEN -> a.sharpen
        DENOISE -> a.denoise
    }

    fun with(a: Adjustments, value: Float): Adjustments {
        val v = value.coerceIn(min, max)
        return when (this) {
            EXPOSURE -> a.copy(exposure = v)
            BRIGHTNESS -> a.copy(brightness = v)
            CONTRAST -> a.copy(contrast = v)
            HIGHLIGHTS -> a.copy(highlights = v)
            SHADOWS -> a.copy(shadows = v)
            WHITES -> a.copy(whites = v)
            BLACKS -> a.copy(blacks = v)
            SATURATION -> a.copy(saturation = v)
            VIBRANCE -> a.copy(vibrance = v)
            TEMPERATURE -> a.copy(temperature = v)
            TINT -> a.copy(tint = v)
            CLARITY -> a.copy(clarity = v)
            DEHAZE -> a.copy(dehaze = v)
            SHARPEN -> a.copy(sharpen = v)
            DENOISE -> a.copy(denoise = v)
        }
    }
}

/**
 * Applies tone, colour and detail adjustments to a bitmap.
 *
 * Split into two stages so cost stays predictable:
 *  - **Tone stage**: one fused per-pixel pass. Every tonal and colour control is
 *    evaluated together against a precomputed luma mask, avoiding a separate
 *    buffer allocation per slider.
 *  - **Detail stage**: neighbourhood operations (denoise, sharpen, clarity) that
 *    genuinely need neighbouring pixels, so they run only when enabled.
 */
class AdjustmentEngine {

    /**
     * Applies adjustments directly to an existing pixel array.
     *
     * This is the hot path used by [PipelineRenderer]: it avoids allocating an
     * intermediate [IntArray] per stage, which is what previously made slider
     * drags stutter on large photos.
     */
    fun applyToPixels(px: IntArray, width: Int, height: Int, a: Adjustments) {
        if (a.isNeutral) return

        if (needsDetailPass(a)) {
            // The detail stages need neighbouring pixels, so they build a
            // scratch result. Copy it back so the caller's array is final.
            val detailed = applyDetail(px, width, height, a)
            if (detailed !== px) System.arraycopy(detailed, 0, px, 0, px.size)
        }
        applyTone(px, a)
    }

    suspend fun apply(source: Bitmap, a: Adjustments): Bitmap =
        withContext(Dispatchers.Default) {
            if (a.isNeutral) return@withContext source

            val width = source.width
            val height = source.height
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)

            val working = if (needsDetailPass(a)) {
                applyDetail(pixels, width, height, a)
            } else {
                pixels
            }

            applyTone(working, a)

            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            out.setPixels(working, 0, width, 0, 0, width, height)
            out
        }

    private fun needsDetailPass(a: Adjustments): Boolean =
        a.denoise != 0f || a.sharpen != 0f || a.clarity != 0f

    private fun applyTone(px: IntArray, a: Adjustments) {
        val exposureMul = exposureMultiplier(a.exposure)
        val contrastSlope = contrastSlope(a.contrast)
        val n = px.size
        val luma = FloatArray(n) { lumaOf(px[it]) }

        val hasHiSh = a.highlights != 0f || a.shadows != 0f
        val hasWhBl = a.whites != 0f || a.blacks != 0f
        val hasWB = a.temperature != 0f || a.tint != 0f
        val hasSat = a.saturation != 0f || a.vibrance != 0f
        val hasDehaze = a.dehaze != 0f

        for (i in 0 until n) {
            val argb = px[i]
            var r = ((argb shr 16) and 0xFF) * INV255
            var g = ((argb shr 8) and 0xFF) * INV255
            var b = (argb and 0xFF) * INV255
            val l = luma[i]

            // Exposure: photographic stops, 2^stops
            r *= exposureMul; g *= exposureMul; b *= exposureMul

            // Brightness: additive
            val bAmt = a.brightness * 0.25f
            r += bAmt; g += bAmt; b += bAmt

            if (hasHiSh) {
                val d = smoothStep(0.5f, 1f, l) * a.highlights * 0.35f +
                    smoothStep(0.5f, 0f, l) * a.shadows * 0.35f
                r += d; g += d; b += d
            }

            if (hasWhBl) {
                val d = smoothStep(0.75f, 1f, l) * a.whites * 0.3f +
                    smoothStep(0.25f, 0f, l) * a.blacks * 0.3f
                r += d; g += d; b += d
            }

            if (a.contrast != 0f) {
                r = (r - 0.5f) * contrastSlope + 0.5f
                g = (g - 0.5f) * contrastSlope + 0.5f
                b = (b - 0.5f) * contrastSlope + 0.5f
            }

            if (hasWB) {
                val t = a.temperature
                val ti = a.tint
                r += t * 0.12f - ti * 0.03f
                g += ti * 0.08f
                b -= t * 0.12f + ti * 0.03f
            }

            if (hasSat) {
                val cur = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val chroma = max(r, max(g, b)) - min(r, min(g, b))
                val vibWeight = (1f - chroma * 2.2f).coerceIn(0f, 1f)
                val total = a.saturation + a.vibrance * vibWeight
                if (total != 0f) {
                    val k = 1f + total
                    r = cur + (r - cur) * k
                    g = cur + (g - cur) * k
                    b = cur + (b - cur) * k
                }
            }

            if (hasDehaze) {
                val d = 1f + a.dehaze * 0.4f
                r = (r - 0.5f) * d + 0.5f
                g = (g - 0.5f) * d + 0.5f
                b = (b - 0.5f) * d + 0.5f
                val lift = a.dehaze * 0.05f
                r += lift; g += lift; b += lift
            }

            px[i] = OPAQUE or (to255(r) shl 16) or (to255(g) shl 8) or to255(b)
        }
    }

    private fun applyDetail(px: IntArray, w: Int, h: Int, a: Adjustments): IntArray {
        var buf = px

        if (a.denoise != 0f) {
            // Edge-aware 3x3 blend: weight drops as local contrast rises so
            // real edges survive while flat noise is smoothed away.
            val out = IntArray(px.size)
            val amount = a.denoise.coerceIn(0f, 1f)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val c = px[i]
                    val cr = (c shr 16) and 0xFF
                    val cg = (c shr 8) and 0xFF
                    val cb = c and 0xFF
                    var sr = 0; var sg = 0; var sb = 0
                    for (dy in -1..1) {
                        val ny = (y + dy).coerceIn(0, h - 1)
                        for (dx in -1..1) {
                            val p = px[ny * w + (x + dx).coerceIn(0, w - 1)]
                            sr += (p shr 16) and 0xFF
                            sg += (p shr 8) and 0xFF
                            sb += p and 0xFF
                        }
                    }
                    val mr = sr / 9; val mg = sg / 9; val mb = sb / 9
                    val diff = absInt(cr - mr) + absInt(cg - mg) + absInt(cb - mb)
                    val k = amount * (1f - diff / 255f * 0.8f).coerceIn(0f, 1f)
                    out[i] = OPAQUE or
                        (lerp255(cr, mr, k) shl 16) or
                        (lerp255(cg, mg, k) shl 8) or
                        lerp255(cb, mb, k)
                }
            }
            buf = out
        }

        if (a.clarity != 0f) {
            // Wide-radius unsharp mask on luminance only, preserving hue.
            val blurred = boxBlurLuma(buf, w, h, radius = 6)
            val out = IntArray(buf.size)
            val amount = a.clarity * 0.6f
            for (i in buf.indices) {
                val c = buf[i]
                val boost = (lumaOf(c) - blurred[i]) * amount
                out[i] = OPAQUE or
                    (to255(((c shr 16) and 0xFF) * INV255 + boost) shl 16) or
                    (to255(((c shr 8) and 0xFF) * INV255 + boost) shl 8) or
                    to255((c and 0xFF) * INV255 + boost)
            }
            buf = out
        }

        if (a.sharpen != 0f) {
            // 3x3 Laplacian convolution scaled by the slider.
            val out = IntArray(buf.size)
            val strength = a.sharpen.coerceIn(0f, 1f) * 1.2f
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val c = buf[i]
                    val cl = lumaOf(buf[indexAt(x - 1, y, w, h)])
                    val cr2 = lumaOf(buf[indexAt(x + 1, y, w, h)])
                    val cu = lumaOf(buf[indexAt(x, y - 1, w, h)])
                    val cd = lumaOf(buf[indexAt(x, y + 1, w, h)])
                    val delta = (lumaOf(c) * 4f - cl - cr2 - cu - cd) * strength
                    out[i] = OPAQUE or
                        (to255(((c shr 16) and 0xFF) * INV255 + delta) shl 16) or
                        (to255(((c shr 8) and 0xFF) * INV255 + delta) shl 8) or
                        to255((c and 0xFF) * INV255 + delta)
                }
            }
            buf = out
        }

        return buf
    }

    private fun indexAt(x: Int, y: Int, w: Int, h: Int): Int =
        y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)

    /** Separable box blur, run twice for an approximate Gaussian. */
    private fun boxBlurLuma(px: IntArray, w: Int, h: Int, radius: Int): FloatArray {
        val tmp = FloatArray(px.size)
        val out = FloatArray(px.size)
        val norm = 1f / (radius * 2 + 1)

        for (y in 0 until h) {
            var acc = 0f
            for (k in -radius..radius) acc += lumaOf(px[indexAt(k, y, w, h)])
            for (x in 0 until w) {
                tmp[y * w + x] = acc * norm
                acc -= lumaOf(px[indexAt(x - radius, y, w, h)])
                acc += lumaOf(px[indexAt(x + radius + 1, y, w, h)])
            }
        }
        for (x in 0 until w) {
            var acc = 0f
            for (k in -radius..radius) acc += tmp[indexAt(x, k, w, h)]
            for (y in 0 until h) {
                out[y * w + x] = acc * norm
                acc -= tmp[indexAt(x, y - radius, w, h)]
                acc += tmp[indexAt(x, y + radius + 1, w, h)]
            }
        }
        return out
    }

    private fun exposureMultiplier(stops: Float): Float =
        2.0.pow(stops.toDouble()).toFloat()

    private fun contrastSlope(v: Float): Float =
        if (v >= 0f) 1f + v * 1.5f else 1f / (1f + (-v) * 1.5f)

    private fun lumaOf(c: Int): Float =
        (0.2126f * ((c shr 16) and 0xFF) +
            0.7152f * ((c shr 8) and 0xFF) +
            0.0722f * (c and 0xFF)) * INV255

    private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun to255(v: Float): Int = (v * 255f).roundToInt().coerceIn(0, 255)

    private fun lerp255(from: Int, to: Int, k: Float): Int =
        (from + (to - from) * k).roundToInt().coerceIn(0, 255)

    private fun absInt(v: Int): Int = if (v < 0) -v else v

    private companion object {
        const val INV255 = 1f / 255f
        const val OPAQUE = 0xFF shl 24
    }
}
