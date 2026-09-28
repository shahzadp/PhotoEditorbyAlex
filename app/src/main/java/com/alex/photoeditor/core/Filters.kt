package com.alex.photoeditor.core

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A named look, applied as a parameterised pixel transform.
 *
 * Every filter is pure data + math so it runs on the CPU identically during
 * preview and export, and stays fully offline with no bundled LUT binaries.
 */
data class PhotoFilter(
    val id: String,
    val name: String,
    val category: FilterCategory,
    val settings: FilterSettings
)

enum class FilterCategory(val label: String) {
    BASIC("Basic"),
    FILM("Film"),
    VINTAGE("Vintage"),
    MONO("Mono"),
    CREATIVE("Creative"),
    COOL("Cool"),
    WARM("Warm")
}

/** Per-filter parameters. Only the fields relevant to a filter are non-zero. */
data class FilterSettings(
    val saturation: Float = 1f,
    val contrast: Float = 1f,
    val brightness: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val fade: Float = 0f,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val halation: Float = 0f,
    val duotoneStrength: Float = 0f,
    val duotoneShadow: Int = 0x000000,
    val duotoneHighlight: Int = 0xFFFFFF,
    val crossProcess: Float = 0f,
    val splitShadow: Float = 0f,
    val splitHighlight: Float = 0f,
    val shadowTint: Int = 0x000000,
    val highlightTint: Int = 0xFFFFFF,
    val monochromeMix: Float = 1f,
    val posterize: Float = 0f,
    val pixelate: Float = 0f,
    val bloom: Float = 0f,
    val solarize: Float = 0f,
    val autoContrast: Float = 0f
)

/** Built-in filter library. */
object FilterLibrary {

    val all: List<PhotoFilter> = listOf(
        // --- Basic -------------------------------------------------------
        PhotoFilter("original", "Original", FilterCategory.BASIC, FilterSettings()),
        PhotoFilter("punch", "Punch", FilterCategory.BASIC,
            FilterSettings(saturation = 1.25f, contrast = 1.15f)),
        PhotoFilter("soft", "Soft", FilterCategory.BASIC,
            FilterSettings(saturation = 0.95f, contrast = 0.9f, brightness = 0.04f)),
        PhotoFilter("crisp", "Crisp", FilterCategory.BASIC,
            FilterSettings(contrast = 1.3f, saturation = 1.1f, autoContrast = 0.4f)),
        PhotoFilter("warm_light", "Warm Light", FilterCategory.WARM,
            FilterSettings(temperature = 0.18f, brightness = 0.05f, saturation = 1.1f)),
        PhotoFilter("cool_light", "Cool Light", FilterCategory.COOL,
            FilterSettings(temperature = -0.18f, saturation = 1.05f)),

        // --- Mono --------------------------------------------------------
        PhotoFilter("bw", "B&W", FilterCategory.MONO,
            FilterSettings(monochromeMix = 1f, contrast = 1.2f)),
        PhotoFilter("bw_soft", "Soft Mono", FilterCategory.MONO,
            FilterSettings(monochromeMix = 1f, contrast = 0.85f, brightness = 0.05f)),
        PhotoFilter("noir", "Noir", FilterCategory.MONO,
            FilterSettings(monochromeMix = 1f, contrast = 1.5f, vignette = 0.45f, grain = 0.12f)),
        PhotoFilter("silver", "Silver", FilterCategory.MONO,
            FilterSettings(monochromeMix = 0.85f, temperature = -0.1f, contrast = 1.1f, fade = 0.15f)),

        // --- Film --------------------------------------------------------
        PhotoFilter("kodachrome", "Kodachrome", FilterCategory.FILM,
            FilterSettings(saturation = 1.3f, contrast = 1.25f, temperature = 0.08f)),
        PhotoFilter("ektachrome", "Ektachrome", FilterCategory.FILM,
            FilterSettings(saturation = 0.95f, contrast = 1.2f, crossProcess = 0.35f)),
        PhotoFilter("portra", "Portra", FilterCategory.FILM,
            FilterSettings(saturation = 0.9f, temperature = 0.12f, fade = 0.25f, contrast = 0.95f)),
        PhotoFilter("ektar", "Ektar", FilterCategory.FILM,
            FilterSettings(saturation = 1.35f, contrast = 1.15f)),
        PhotoFilter("tri_x", "Tri-X", FilterCategory.FILM,
            FilterSettings(monochromeMix = 0.7f, contrast = 1.35f, grain = 0.2f, fade = 0.1f)),
        PhotoFilter("cinestill", "Cine Still", FilterCategory.FILM,
            FilterSettings(temperature = -0.1f, splitShadow = 0.4f,
                shadowTint = 0x1B3A5C, splitHighlight = 0.15f, highlightTint = 0xFFD9A0,
                saturation = 0.95f, grain = 0.15f)),
        PhotoFilter("faded_film", "Faded Film", FilterCategory.VINTAGE,
            FilterSettings(fade = 0.45f, saturation = 0.7f, temperature = 0.1f, contrast = 0.85f)),
        PhotoFilter("lomography", "Lomography", FilterCategory.VINTAGE,
            FilterSettings(saturation = 1.4f, contrast = 1.1f, halation = 0.4f,
                shadowTint = 0x2A4A7A, splitShadow = 0.25f, grain = 0.2f)),

        // --- Vintage -----------------------------------------------------
        PhotoFilter("sepia", "Sepia", FilterCategory.VINTAGE,
            FilterSettings(duotoneStrength = 0.85f, duotoneShadow = 0x3A2410, duotoneHighlight = 0xF5E6C8,
                contrast = 1.1f)),
        PhotoFilter("selenium", "Selenium", FilterCategory.VINTAGE,
            FilterSettings(duotoneStrength = 0.8f, duotoneShadow = 0x1A2438, duotoneHighlight = 0xE8D5B5)),
        PhotoFilter("cyanotype", "Cyanotype", FilterCategory.VINTAGE,
            FilterSettings(duotoneStrength = 0.9f, duotoneShadow = 0x0A1E3A, duotoneHighlight = 0xBFE0F5)),
        PhotoFilter("polaroid", "Polaroid", FilterCategory.VINTAGE,
            FilterSettings(fade = 0.3f, temperature = 0.1f, saturation = 0.9f, brightness = 0.06f)),

        // --- Creative ----------------------------------------------------
        PhotoFilter("vhs", "VHS", FilterCategory.CREATIVE,
            FilterSettings(saturation = 1.3f, crossProcess = 0.5f, grain = 0.25f,
                splitShadow = 0.3f, shadowTint = 0x2B1A4A, bloom = 0.2f)),
        PhotoFilter("glitch", "Glitch", FilterCategory.CREATIVE,
            FilterSettings(contrast = 1.2f, splitShadow = 0.4f, splitHighlight = 0.3f,
                crossProcess = 0.6f, grain = 0.2f)),
        PhotoFilter("posterize", "Posterize", FilterCategory.CREATIVE,
            FilterSettings(posterize = 6f, saturation = 1.1f)),
        PhotoFilter("solarize", "Solarize", FilterCategory.CREATIVE,
            FilterSettings(solarize = 0.5f, saturation = 1.2f)),
        PhotoFilter("duotone", "Duotone", FilterCategory.CREATIVE,
            FilterSettings(duotoneStrength = 0.9f, duotoneShadow = 0x2D0A4A, duotoneHighlight = 0xFFD6A8)),
        PhotoFilter("pixelate", "Pixelate", FilterCategory.CREATIVE,
            FilterSettings(pixelate = 8f)),
        PhotoFilter("bloom", "Bloom", FilterCategory.CREATIVE,
            FilterSettings(bloom = 0.55f, brightness = 0.04f)),
        PhotoFilter("grain", "Grain", FilterCategory.FILM,
            FilterSettings(grain = 0.5f)),

        // --- Cool --------------------------------------------------------
        PhotoFilter("arctic", "Arctic", FilterCategory.COOL,
            FilterSettings(temperature = -0.3f, saturation = 0.95f,
                splitShadow = 0.3f, shadowTint = 0x1E4A6E)),
        PhotoFilter("moonlight", "Moonlight", FilterCategory.COOL,
            FilterSettings(temperature = -0.25f, monochromeMix = 0.4f, contrast = 1.2f,
                splitShadow = 0.35f, shadowTint = 0x2A3A5A)),
        PhotoFilter("teal_orange", "Teal & Orange", FilterCategory.CREATIVE,
            FilterSettings(splitShadow = 0.5f, shadowTint = 0x1A5A66, splitHighlight = 0.4f,
                highlightTint = 0xFFB070, saturation = 1.15f)),
        PhotoFilter("matrix", "Matrix", FilterCategory.COOL,
            FilterSettings(duotoneStrength = 0.95f, duotoneShadow = 0x031A0A, duotoneHighlight = 0x5CFF9E)),
        PhotoFilter("infrared", "Infrared", FilterCategory.CREATIVE,
            FilterSettings(duotoneStrength = 0.85f, duotoneShadow = 0x2A0A0A, duotoneHighlight = 0xFFE0C0))
    )

    val byId: Map<String, PhotoFilter> = all.associateBy { it.id }

    fun byCategory(): Map<FilterCategory, List<PhotoFilter>> = all.groupBy { it.category }
}

/** Applies a [PhotoFilter] to a bitmap. */
class FilterEngine {

    /**
     * Applies a filter directly to an existing pixel array, avoiding an extra
     * [IntArray] allocation. Used by [PipelineRenderer] on the slider path.
     *
     * Neighbourhood effects (pixelate, bloom, halation) still build a temporary
     * array, but copy the result back into [px] so the caller's buffer is final.
     */
    fun applyToPixels(
        px: IntArray,
        width: Int,
        height: Int,
        filter: PhotoFilter
    ) {
        if (filter.id == "original") return
        val s = filter.settings

        if (s.pixelate > 0f) {
            copyBack(px, pixelate(px, width, height, s.pixelate))
        }
        if (s.bloom > 0f) {
            copyBack(px, bloom(px, width, height, s.bloom))
        }
        if (s.halation > 0f) {
            copyBack(px, halation(px, width, height, s.halation))
        }

        // colorPass and tonalPass allocate their own output; fold each result
        // straight back in to keep the caller's buffer authoritative.
        copyBack(px, colorPass(px, s))
        copyBack(px, tonalPass(px, s))

        if (s.grain > 0f) copyBack(px, grain(px, s.grain))
        if (s.vignette > 0f) copyBack(px, vignette(px, width, height, s.vignette))
    }

    private fun copyBack(dst: IntArray, src: IntArray) {
        if (src !== dst) System.arraycopy(src, 0, dst, 0, dst.size)
    }

    suspend fun apply(source: Bitmap, filter: PhotoFilter): Bitmap =
        withContext(Dispatchers.Default) {
            val s = filter.settings
            if (filter.id == "original") return@withContext source

            val width = source.width
            val height = source.height
            var pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)

            // Neighbourhood effects first, tonal effects after.
            if (s.pixelate > 0f) pixels = pixelate(pixels, width, height, s.pixelate)
            if (s.bloom > 0f) pixels = bloom(pixels, width, height, s.bloom)
            if (s.halation > 0f) pixels = halation(pixels, width, height, s.halation)

            pixels = colorPass(pixels, s)
            pixels = tonalPass(pixels, s)

            if (s.grain > 0f) pixels = grain(pixels, s.grain)
            if (s.vignette > 0f) pixels = vignette(pixels, width, height, s.vignette)

            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            out.setPixels(pixels, 0, width, 0, 0, width, height)
            out
        }

    // -------------------------------------------------------------- colour

    private fun colorPass(px: IntArray, s: FilterSettings): IntArray {
        val out = IntArray(px.size)

        for (i in px.indices) {
            val c = px[i]
            var r = ((c shr 16) and 0xFF) * INV255
            var g = ((c shr 8) and 0xFF) * INV255
            var b = (c and 0xFF) * INV255

            // Monochrome (luminance) with a touch of channel mix for film feel
            if (s.monochromeMix > 0f) {
                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val k = s.monochromeMix
                r += (l - r) * k
                g += (l - g) * k
                b += (l - b) * k
            }

            // Saturation
            if (s.saturation != 1f) {
                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                r = l + (r - l) * s.saturation
                g = l + (g - l) * s.saturation
                b = l + (b - l) * s.saturation
            }

            // White balance
            if (s.temperature != 0f) {
                r += s.temperature * 0.12f
                b -= s.temperature * 0.12f
            }
            if (s.tint != 0f) {
                g += s.tint * 0.08f
                r -= s.tint * 0.03f
                b -= s.tint * 0.03f
            }

            // Cross processing: lift shadows, crush highlights, swap channel balance
            if (s.crossProcess > 0f) {
                val k = s.crossProcess
                r = r * (1f + k * 0.15f)
                b = b * (1f - k * 0.10f)
                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                r = l + (r - l) * (1f + k * 0.2f)
                g = l + (g - l) * (1f - k * 0.15f)
            }

            // Split toning
            if (s.splitShadow > 0f || s.splitHighlight > 0f) {
                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val shW = (1f - smoothStep(0f, 1f, l)) * s.splitShadow
                val hiW = smoothStep(0f, 1f, l) * s.splitHighlight
                if (shW > 0f) {
                    val t = unpack(s.shadowTint)
                    r += t.first * shW; g += t.second * shW; b += t.third * shW
                }
                if (hiW > 0f) {
                    val t = unpack(s.highlightTint)
                    r += t.first * hiW; g += t.second * hiW; b += t.third * hiW
                }
            }

            // Duotone mapping
            if (s.duotoneStrength > 0f) {
                val l = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
                val sh = unpack(s.duotoneShadow)
                val hi = unpack(s.duotoneHighlight)
                val nr = lerp(r, sh.first + (hi.first - sh.first) * l, s.duotoneStrength)
                val ng = lerp(g, sh.second + (hi.second - sh.second) * l, s.duotoneStrength)
                val nb = lerp(b, sh.third + (hi.third - sh.third) * l, s.duotoneStrength)
                r = nr; g = ng; b = nb
            }

            // Posterize
            if (s.posterize > 0f) {
                val levels = (s.posterize.coerceIn(2f, 32f) - 1f).roundToInt()
                r = (r * levels).roundToInt() / levels.toFloat()
                g = (g * levels).roundToInt() / levels.toFloat()
                b = (b * levels).roundToInt() / levels.toFloat()
            }

            // Solarize
            if (s.solarize > 0f) {
                val t = s.solarize
                val f = { v: Float -> if (v > 0.5f) v - (v - 0.5f) * 2f * t else v + (0.5f - v) * 2f * t }
                r = f(r); g = f(g); b = f(b)
            }

            out[i] = OPAQUE or (to255(r) shl 16) or (to255(g) shl 8) or to255(b)
        }
        return out
    }

    // --------------------------------------------------------------- tonal

    private fun tonalPass(px: IntArray, s: FilterSettings): IntArray {
        val out = IntArray(px.size)

        // Fade = lift blacks and roll off highlights (matte look)
        val fadeLift = s.fade * 0.22f
        val hasTonal = s.contrast != 1f || s.brightness != 0f || s.fade != 0f || s.autoContrast > 0f

        for (i in px.indices) {
            val c = px[i]
            var r = ((c shr 16) and 0xFF) * INV255
            var g = ((c shr 8) and 0xFF) * INV255
            var b = (c and 0xFF) * INV255

            if (hasTonal) {
                r += s.brightness; g += s.brightness; b += s.brightness

                if (s.contrast != 1f) {
                    val k = s.contrast
                    r = (r - 0.5f) * k + 0.5f
                    g = (g - 0.5f) * k + 0.5f
                    b = (b - 0.5f) * k + 0.5f
                }

                if (fadeLift > 0f) {
                    // lift toward mid-grey proportionally
                    r += (0.5f - r) * fadeLift
                    g += (0.5f - g) * fadeLift
                    b += (0.5f - b) * fadeLift
                }
            }

            out[i] = OPAQUE or (to255(r) shl 16) or (to255(g) shl 8) or to255(b)
        }
        return out
    }

    // ------------------------------------------------------- neighbourhood

    private fun pixelate(px: IntArray, w: Int, h: Int, blockSize: Float): IntArray {
        val out = IntArray(px.size)
        val bs = blockSize.roundToInt().coerceIn(2, 64)
        for (by in 0 until h step bs) {
            for (bx in 0 until w step bs) {
                var sr = 0; var sg = 0; var sb = 0; var n = 0
                for (y in by until min(by + bs, h)) {
                    for (x in bx until min(bx + bs, w)) {
                        val p = px[y * w + x]
                        sr += (p shr 16) and 0xFF
                        sg += (p shr 8) and 0xFF
                        sb += p and 0xFF
                        n++
                    }
                }
                val c = OPAQUE or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                for (y in by until min(by + bs, h)) {
                    for (x in bx until min(bx + bs, w)) {
                        out[y * w + x] = c
                    }
                }
            }
        }
        return out
    }

    private fun bloom(px: IntArray, w: Int, h: Int, amount: Float): IntArray {
        val blurred = blur(px, w, h, radius = max(2, min(w, h) / 60))
        val out = IntArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            val b = blurred[i]
            // screen blend of the blurred highlights back over the original
            val br = ((c shr 16) and 0xFF) * INV255
            val bg = ((c shr 8) and 0xFF) * INV255
            val bb = (c and 0xFF) * INV255
            val hr = ((b shr 16) and 0xFF) * INV255
            val hg = ((b shr 8) and 0xFF) * INV255
            val hb = (b and 0xFF) * INV255
            out[i] = OPAQUE or
                (to255(screen(br, hr * amount)) shl 16) or
                (to255(screen(bg, hg * amount)) shl 8) or
                to255(screen(bb, hb * amount))
        }
        return out
    }

    private fun halation(px: IntArray, w: Int, h: Int, amount: Float): IntArray {
        val blurred = blur(px, w, h, radius = max(3, min(w, h) / 30))
        val out = IntArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            val b = blurred[i]
            // isolate warm highlights from the blur
            val hr = max(0f, ((b shr 16) and 0xFF) * INV255 - 0.55f) * 2f
            val hg = max(0f, ((b shr 8) and 0xFF) * INV255 - 0.55f) * 2f
            val hb = max(0f, (b and 0xFF) * INV255 - 0.55f) * 2f
            val k = amount
            out[i] = OPAQUE or
                (to255(((c shr 16) and 0xFF) * INV255 + hr * k) shl 16) or
                (to255(((c shr 8) and 0xFF) * INV255 + hg * k * 0.6f) shl 8) or
                to255((c and 0xFF) * INV255 + hb * k * 0.3f)
        }
        return out
    }

    private fun blur(px: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val tmp = IntArray(px.size)
        val out = IntArray(px.size)
        val d = radius * 2 + 1

        for (y in 0 until h) {
            for (x in 0 until w) {
                var sr = 0; var sg = 0; var sb = 0
                for (k in -radius..radius) {
                    val p = px[y * w + (x + k).coerceIn(0, w - 1)]
                    sr += (p shr 16) and 0xFF
                    sg += (p shr 8) and 0xFF
                    sb += p and 0xFF
                }
                tmp[y * w + x] = OPAQUE or ((sr / d) shl 16) or ((sg / d) shl 8) or (sb / d)
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sr = 0; var sg = 0; var sb = 0
                for (k in -radius..radius) {
                    val p = tmp[(y + k).coerceIn(0, h - 1) * w + x]
                    sr += (p shr 16) and 0xFF
                    sg += (p shr 8) and 0xFF
                    sb += p and 0xFF
                }
                out[y * w + x] = OPAQUE or ((sr / d) shl 16) or ((sg / d) shl 8) or (sb / d)
            }
        }
        return out
    }

    // ------------------------------------------------------------ overlays

    private fun grain(px: IntArray, amount: Float): IntArray {
        val out = IntArray(px.size)
        val strength = amount * 0.25f
        // deterministic PRNG keeps previews stable frame to frame
        var seed = 0x5DEECE66DL
        for (i in px.indices) {
            seed = (seed * 0x5DEECE66DL + 0xBL) and ((1L shl 48) - 1)
            val n = ((seed shr 16).toInt() and 0xFF) / 255f - 0.5f
            val c = px[i]
            out[i] = OPAQUE or
                (to255(((c shr 16) and 0xFF) * INV255 + n * strength) shl 16) or
                (to255(((c shr 8) and 0xFF) * INV255 + n * strength) shl 8) or
                to255((c and 0xFF) * INV255 + n * strength)
        }
        return out
    }

    private fun vignette(px: IntArray, w: Int, h: Int, amount: Float): IntArray {
        val out = IntArray(px.size)
        val cx = w / 2f
        val cy = h / 2f
        val maxDist = sqrt(cx * cx + cy * cy)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x - cx
                val dy = y - cy
                val d = sqrt(dx * dx + dy * dy) / maxDist
                val falloff = (d * d * amount).coerceIn(0f, 0.9f)
                val c = px[y * w + x]
                out[y * w + x] = OPAQUE or
                    (to255(((c shr 16) and 0xFF) * INV255 * (1f - falloff)) shl 16) or
                    (to255(((c shr 8) and 0xFF) * INV255 * (1f - falloff)) shl 8) or
                    to255((c and 0xFF) * INV255 * (1f - falloff))
            }
        }
        return out
    }

    // ------------------------------------------------------------- helpers

    private fun unpack(color: Int): Triple<Float, Float, Float> = Triple(
        (((color shr 16) and 0xFF) * INV255),
        (((color shr 8) and 0xFF) * INV255),
        ((color and 0xFF) * INV255)
    )

    private fun screen(base: Float, blend: Float): Float =
        1f - (1f - base.coerceIn(0f, 1f)) * (1f - blend.coerceIn(0f, 1f))

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    private fun smoothStep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun to255(v: Float): Int = (v * 255f).roundToInt().coerceIn(0, 255)

    private companion object {
        const val INV255 = 1f / 255f
        const val OPAQUE = 0xFF shl 24
    }
}
