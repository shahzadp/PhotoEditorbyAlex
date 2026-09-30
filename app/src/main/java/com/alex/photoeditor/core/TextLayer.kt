package com.alex.photoeditor.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import kotlinx.serialization.Serializable

/**
 * A piece of text stacked over the base photo.
 *
 * Deliberately a *separate* list from [ImageLayer] rather than a variant of it.
 * The two have almost nothing in common: an image layer owns a decoded bitmap,
 * a text layer owns a string and a typeface. A shared type would have meant
 * nullable fields for both sets and a type check on every access, and the two
 * also need independent caps — ten images plus ten texts, not ten in total.
 *
 * Text is rendered as vectors on the Canvas rather than rasterised into a
 * bitmap per layer. That keeps twenty text layers costing no extra memory, and
 * it is what lets the text stay crisp when the layer is scaled or the export
 * resolution differs from the preview.
 */
@Serializable
data class TextLayer(
    val id: String,
    val text: String = "Text",
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** Font size as a fraction of the base image's *shorter* edge. */
    val sizeFraction: Float = 0.06f,
    val rotationDegrees: Float = 0f,
    /** 0xAARRGGBB */
    val color: Long = 0xFFFFFFFFL,
    /** Key into [TextFonts], not a raw family name, so it stays serialisable. */
    val fontKey: String = TextFonts.DEFAULT_KEY,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** 0..1 */
    val opacity: Float = 1f,
    val visible: Boolean = true
) {
    companion object {
        /**
         * Maximum stacked text objects.
         *
         * Independent of [ImageLayer.MAX_LAYERS], so a photo can carry twenty
         * overlaid objects in total.
         */
        const val MAX_TEXT_LAYERS = 10

        /**
         * Human-readable label derived from stack position, matching
         * [ImageLayer.labelFor] so the two lists read consistently.
         */
        fun labelFor(index: Int): String = "Text ${index + 1}"
    }
}

/**
 * The font families offered.
 *
 * Referenced by key rather than by name so that renaming or removing a family
 * cannot silently break a saved project: a key with no entry falls back to the
 * default rather than throwing.
 */
object TextFonts {

    const val DEFAULT_KEY = "sans"

    data class Option(
        val key: String,
        val label: String,
        /** Null lets the platform pick the system default. */
        val family: String?
    )

    val options: List<Option> = listOf(
        Option("sans", "Sans", null),
        Option("serif", "Serif", "serif"),
        Option("monospace", "Mono", "monospace"),
        Option("casual", "Casual", "cursive"),
        // The platform sans, which on most devices is already loaded and so
        // costs nothing extra to reference.
        Option("condensed", "Narrow", "sans-serif-condensed")
    )

    fun optionFor(key: String): Option =
        options.firstOrNull { it.key == key } ?: options.first()

    /**
     * Builds the typeface for a layer.
     *
     * The style flags are OR-ed onto the base style rather than replacing it,
     * so bold-italic composes instead of one silently winning.
     */
    fun typefaceFor(layer: TextLayer): Typeface {
        val family = optionFor(layer.fontKey).family
        var style = Typeface.NORMAL
        if (layer.bold) style = style or Typeface.BOLD
        if (layer.italic) style = style or Typeface.ITALIC
        return Typeface.create(family, style)
    }
}

/**
 * Draws text layers onto a finished base render.
 *
 * Same contract as [LayerComposer]: returns a new bitmap and never recycles
 * what it was given. One [Paint] and one [android.graphics.Matrix] are reused
 * across every layer, so compositing twenty of them allocates nothing per
 * frame beyond the single output copy.
 */
object TextComposer {

    fun compose(
        base: Bitmap,
        layers: List<TextLayer>
    ): Bitmap {
        if (layers.isEmpty()) return base
        if (layers.none { it.visible && it.opacity > 0f && it.text.isNotBlank() }) return base

        val out = base.copy(Bitmap.Config.ARGB_8888, true) ?: return base
        val canvas = Canvas(out)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        val matrix = Matrix()

        for (layer in layers) {
            if (!layer.visible || layer.opacity <= 0f || layer.text.isBlank()) continue

            val shortEdge = minOf(base.width, base.height).toFloat()
            val textSize = layer.sizeFraction * shortEdge
            if (textSize <= 0f) continue

            paint.textSize = textSize
            paint.typeface = TextFonts.typefaceFor(layer)
            // Sized as a fraction of the glyph rather than a fixed dp, so the
            // halo stays proportional at every export resolution.
            paint.strokeWidth = textSize * STROKE_RATIO
            val alpha = (layer.opacity.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)

            val fill = layer.color.toInt()
            val rgb = fill and 0x00FFFFFF
            val alphaOnly = (alpha shl 24)

            // Centred on the layer's position, matching how [LayerComposer]
            // places an image: the anchor is the object's middle, not its
            // top-left.
            matrix.reset()
            matrix.postTranslate(
                -paint.measureText(layer.text) / 2f,
                -paint.fontMetrics.let { (it.ascent + it.descent) / 2f }
            )
            matrix.postRotate(layer.rotationDegrees)
            matrix.postTranslate(
                base.width * 0.5f + base.width * layer.offsetX,
                base.height * 0.5f + base.height * layer.offsetY
            )

            canvas.save()
            canvas.concat(matrix)

            // Two passes: a dark halo first, then the fill on top. A single
            // pass cannot do this — Paint.Style.STROKE and Style.FILL are
            // mutually exclusive, and setting FILL discards the stroke
            // entirely. The halo is what keeps text readable over an
            // arbitrary photo without a separate shadow or blur pass.
            paint.style = Paint.Style.STROKE
            paint.color = Color.argb(
                alpha,
                (Color.red(rgb) * 0.2f).toInt(),
                (Color.green(rgb) * 0.2f).toInt(),
                (Color.blue(rgb) * 0.2f).toInt()
            )
            canvas.drawText(layer.text, 0f, 0f, paint)

            paint.style = Paint.Style.FILL
            paint.color = alphaOnly or rgb
            canvas.drawText(layer.text, 0f, 0f, paint)

            canvas.restore()
        }

        return out
    }

    /** Halo thickness as a fraction of the font size. */
    private const val STROKE_RATIO = 0.06f
}
