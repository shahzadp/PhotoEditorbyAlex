package com.alex.photoeditor.core

import android.graphics.Bitmap

/**
 * Picks a colour from a rendered image.
 *
 * Samples the *flattened preview* rather than the base photo. That is the
 * whole point: a tap on a spot where an added image or a text object sits must
 * return that object's colour, not whatever is underneath it. Because the
 * preview already has layers, text and the cutout composited into it, reading
 * it back answers "what colour is the user pointing at" in one step and needs
 * no knowledge of the layer stack at all.
 */
object Eyedropper {

    /**
     * Colour at a normalised point in [preview], as packed ARGB.
     *
     * @param x 0..1 across the preview's width.
     * @param y 0..1 down the preview's height.
     * @return null if the point lies outside the bitmap, so the caller can
     *         ignore a stray touch rather than sampling a clamped edge.
     */
    fun sample(preview: Bitmap?, x: Float, y: Float): Long? {
        if (preview == null || preview.isRecycled) return null
        if (preview.width <= 0 || preview.height <= 0) return null

        val px = (x.coerceIn(0f, 1f) * (preview.width - 1)).toInt()
        val py = (y.coerceIn(0f, 1f) * (preview.height - 1)).toInt()

        // A one-pixel getPixel avoids allocating an IntArray for what is a
        // single read, which matters because this runs on every tap.
        val argb = preview.getPixel(px, py)
        return argb.toLong() and 0xFFFFFFFFL
    }
}
