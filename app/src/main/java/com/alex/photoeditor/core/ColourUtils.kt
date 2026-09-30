package com.alex.photoeditor.core

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Colour maths shared by every colour control in the app.
 *
 * Kept free of Compose types so the same conversions can be used by the
 * renderer, by project serialisation and by the UI. Text colour and the
 * upcoming draw tool will both read from here rather than each reimplementing
 * HSV conversion slightly differently.
 */
object ColourUtils {

    fun toArgbLong(color: Color): Long =
        (color.alpha.toLong() shl 24) or
            (color.red.toLong() shl 16) or
            (color.green.toLong() shl 8) or
            color.blue.toLong()

    fun fromArgbLong(value: Long): Color = Color(
        red = ((value shr 16) and 0xFF).toFloat() / 255f,
        green = ((value shr 8) and 0xFF).toFloat() / 255f,
        blue = (value and 0xFF).toFloat() / 255f,
        alpha = ((value shr 24) and 0xFF).toFloat() / 255f
    )

    /** Formats as `#RRGGBB`, upper case, which is what users expect to type. */
    fun toHex(value: Long): String =
        "#" + (value and 0x00FFFFFF).toString(16).uppercase().padStart(6, '0')

    /**
     * Parses `#RGB`, `#RRGGBB` or `#AARRGGBB`, tolerating a missing `#`.
     *
     * Returns null on anything unparseable so the caller can reject the input
     * rather than silently storing a wrong colour.
     */
    fun parseHex(input: String): Long? {
        var text = input.trim().removePrefix("#")
        if (text.isEmpty()) return null
        if (text.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.not()) return null

        // #RGB shorthand, e.g. #F0A -> #FF00AA
        if (text.length == 3) {
            text = text.map { "$it$it" }.joinToString("")
        }
        if (text.length == 6) {
            text = "FF$text"
        }
        if (text.length != 8) return null

        return text.toLongOrNull(16)
    }

    /** HSV (all 0..1) to packed ARGB. */
    fun hsvToArgb(h: Float, s: Float, v: Float, alpha: Float = 1f): Long {
        val hh = ((h % 1f) + 1f) % 1f
        val c = v * s
        val x = c * (1f - abs((hh * 6f) % 2f - 1f))
        val m = v - c

        val r: Float
        val g: Float
        val b: Float
        when ((hh * 6f).toInt()) {
            0 -> { r = c; g = x; b = 0f }
            1 -> { r = x; g = c; b = 0f }
            2 -> { r = 0f; g = c; b = x }
            3 -> { r = 0f; g = x; b = c }
            4 -> { r = x; g = 0f; b = c }
            else -> { r = c; g = 0f; b = x }
        }

        val ri = ((r + m) * 255f).roundToInt().coerceIn(0, 255)
        val gi = ((g + m) * 255f).roundToInt().coerceIn(0, 255)
        val bi = ((b + m) * 255f).roundToInt().coerceIn(0, 255)
        val ai = (alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)

        return (ai.toLong() shl 24) or (ri.toLong() shl 16) or
            (gi.toLong() shl 8) or bi.toLong()
    }

    /** Packed ARGB to HSV, as a triple of 0..1 values. */
    fun argbToHsv(value: Long): Triple<Float, Float, Float> {
        val r = ((value shr 16) and 0xFF).toFloat() / 255f
        val g = ((value shr 8) and 0xFF).toFloat() / 255f
        val b = (value and 0xFF).toFloat() / 255f

        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min

        val h = when {
            delta == 0f -> 0f
            max == r -> (((g - b) / delta) % 6f) / 6f
            max == g -> (((b - r) / delta) + 2f) / 6f
            else -> (((r - g) / delta) + 4f) / 6f
        }
        val s = if (max == 0f) 0f else delta / max
        return Triple(if (h < 0f) h + 1f else h, s, max)
    }

    /**
     * A pleasing, evenly spaced swatch palette.
     *
     * Generated in HSL rather than hand-picked so the ramps stay balanced when
     * the set changes, and so the same list serves the text tool and the draw
     * tool without duplicating it.
     */
    fun swatches(count: Int = 12): List<Long> = List(count) { i ->
        val hue = i.toFloat() / count
        hsvToArgb(hue, 0.62f, 0.92f)
    }
}
