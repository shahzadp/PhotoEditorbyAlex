package com.alex.photoeditor.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlinx.serialization.Serializable

/**
 * One image stacked on top of the base photo.
 *
 * Only the file path and transform are stored, never pixels, so the edit state
 * stays small and serialisable. The bitmap is loaded on demand by
 * [LayerStore] and cached there.
 *
 * [offsetX]/[offsetY] are in normalised units of the *base* image, so a layer
 * keeps its position when the export resolution differs from the preview.
 */
@Serializable
data class ImageLayer(
    val id: String,
    /** Absolute path to the app-private copy of the image. */
    val sourcePath: String,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** Width as a fraction of the base image width. 1f means full width. */
    val scale: Float = 0.5f,
    val rotationDegrees: Float = 0f,
    /** 0..1 */
    val opacity: Float = 1f,
    val visible: Boolean = true
) {
    companion object {
        /**
         * Maximum stacked images.
         *
         * Ten is comfortable because [LayerStore] now caps total decoded bytes
         * rather than caching every layer at full size — the memory cost is
         * bounded by the budget, not by the layer count.
         */
        const val MAX_LAYERS = 10

        /**
         * Longest edge a layer bitmap is decoded at.
         *
         * A layer only ever covers part of the base image, and a 4096px decode
         * per layer would be ~64MB each. Combined with [LayerStore]'s byte
         * budget this keeps ten layers affordable.
         */
        const val DECODE_EDGE = 1600

        fun defaultScale(): Float = 0.5f

        /**
         * Decodes a small preview bitmap for the layer list.
         *
         * Kept out of the main cache on purpose: a thumbnail is a few kilobytes
         * and permanently resident, while the full decode is megabytes and
         * should be evictable. Mixing them would let ten resident thumbnails
         * push the layer being dragged out of the byte budget.
         *
         * Sampled by a fixed factor rather than a target edge: the source
         * dimensions are unknown until a separate bounds pass reads them, and
         * these files have already been imported, so 1/8 is a few kilobytes at
         * worst and ample for a 40dp chip.
         */
        fun decodeThumbnail(path: String): Bitmap? = runCatching {
            val opts = android.graphics.BitmapFactory.Options()
            opts.inSampleSize = 8
            opts.inPreferredConfig = Bitmap.Config.RGB_565
            android.graphics.BitmapFactory.decodeFile(path, opts)
        }.getOrNull()

        /**
         * Human-readable label for the panel.
         *
         * Derived from position in the stack rather than the id, so the user
         * sees "Layer 3" sitting third from the bottom instead of a
         * meaningless UUID prefix that gives no clue which is which.
         *
         * @param index position in [EditState.layers], 0 = bottom-most.
         */
        fun labelFor(index: Int): String = "Layer ${index + 1}"
    }
}

/**
 * Loads and caches layer bitmaps.
 *
 * Decoding is the expensive part, so each layer is decoded once and reused
 * across re-renders.
 *
 * The cache is a byte-budgeted LRU rather than a plain map. With the cap now
 * at ten layers, caching every one at [ImageLayer.DECODE_EDGE] unconditionally
 * would hold well over 100MB of bitmaps and start GC-thrashing mid-drag. The
 * budget makes total memory independent of the layer count, and evicting the
 * least-recently-used entry only costs a re-decode if the user scrolls back
 * to it.
 *
 * Entries are never recycled on eviction: a render in flight may still be
 * drawing them. The GC reclaims them once no reference remains, which matches
 * how the rest of the app treats bitmaps.
 */
class LayerStore {

    private val cache = LinkedHashMap<String, Bitmap>(16, 0.75f, /* accessOrder = */ true)

    /** Decoded bytes currently held. */
    private var cachedBytes = 0L

    /**
     * Returns the layer bitmap, decoding it on first use.
     *
     * Returns null if the file has gone (the user may have cleared app data),
     * so a missing layer degrades to "not drawn" rather than crashing.
     */
    fun bitmapFor(layer: ImageLayer): Bitmap? {
        cache[layer.id]?.let {
            // Touching the entry marks it most-recently-used.
            if (!it.isRecycled) return it
            cachedBytes -= byteSizeOf(it)
            cache.remove(layer.id)
        }

        val file = java.io.File(layer.sourcePath)
        if (!file.exists()) return null

        val decoded = decodeBounded(file.path) ?: return null
        cache[layer.id] = decoded
        cachedBytes += byteSizeOf(decoded)
        trimToBudget()
        return decoded
    }

    /**
     * Evicts least-recently-used entries until the cache fits the budget.
     *
     * Always keeps the entry just inserted, even if it alone exceeds the
     * budget: dropping it would mean re-decoding on every single frame.
     */
    private fun trimToBudget() {
        val iterator = cache.entries.iterator()
        while (cachedBytes > MAX_CACHED_BYTES && cache.size > 1) {
            val victim = iterator.next()
            // The most recently used entry is last in access order; stop
            // before evicting it.
            if (victim.key == cache.entries.last().key) break
            cachedBytes -= byteSizeOf(victim.value)
            iterator.remove()
        }
    }

    private fun byteSizeOf(bmp: Bitmap): Long =
        bmp.width.toLong() * bmp.height.toLong() * BYTES_PER_PIXEL

    /**
     * Decodes with a size hint so a huge photo is never fully materialised.
     */
    private fun decodeBounded(path: String): Bitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeFile(path, bounds)

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (longEdge / (sample * 2) >= ImageLayer.DECODE_EDGE) {
            sample *= 2
        }

        android.graphics.BitmapFactory.decodeFile(
            path,
            android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
                // Layers may be drawn scaled up, so a mutable ARGB bitmap is
                // required rather than the default immutable RGB565/F565 form.
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
        )
    }.getOrNull()

    /** Drops a layer's bitmap, e.g. when it is removed from the stack. */
    fun evict(id: String) {
        cache.remove(id)?.let { cachedBytes -= byteSizeOf(it) }
    }

    fun clear() {
        cache.clear()
        cachedBytes = 0
    }

    /**
     * Recycles cached bitmaps. Only safe when no render is in flight, e.g.
     * when the image is closed.
     */
    fun release() {
        cache.values.forEach { if (!it.isRecycled) it.recycle() }
        cache.clear()
        cachedBytes = 0
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4L

        /**
         * Total decoded layer bytes held at once.
         *
         * Sized to comfortably fit alongside the base image and its render
         * buffers on a mid-range phone while still allowing several layers to
         * stay decoded during a drag.
         */
        const val MAX_CACHED_BYTES = 72L * 1024 * 1024
    }
}

/**
 * Draws layers onto a finished base render.
 *
 * Kept separate from the pixel pipeline because this is a geometric
 * compositing step, not a per-pixel colour transform: it needs a Canvas, and
 * running it before the filter/colour stages would apply those stages to the
 * layers too.
 */
object LayerComposer {

    /**
     * Returns a new bitmap with [layers] drawn over [base].
     *
     * The base is never recycled; the result is always a new bitmap so the
     * caller keeps ownership of whatever it passed in.
     *
     * [bitmapFor] is a lookup rather than a [LayerStore] so the compositing
     * step has no opinion about caching or decoding.
     */
    fun compose(
        base: Bitmap,
        layers: List<ImageLayer>,
        bitmapFor: (ImageLayer) -> Bitmap?
    ): Bitmap {
        if (layers.isEmpty()) return base

        // Nothing visible means nothing to draw. Returning the base untouched
        // avoids a full-resolution copy on every render of a fully hidden
        // stack, which is the common case right after the user taps the eye on
        // every layer.
        if (layers.none { it.visible && it.opacity > 0f }) return base

        val out = base.copy(Bitmap.Config.ARGB_8888, true) ?: return base
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Reuse one Matrix across layers rather than allocating per draw.
        val matrix = Matrix()
        for (layer in layers) {
            if (!layer.visible || layer.opacity <= 0f) continue

            val bmp = bitmapFor(layer) ?: continue
            buildMatrix(base, layer, bmp, matrix)
            paint.alpha = (layer.opacity.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)

            canvas.drawBitmap(bmp, matrix, paint)
        }

        return out
    }

    /**
     * Maps a layer into base-image space.
     *
     * The layer is scaled to [ImageLayer.scale] of the base width, centred, then
     * translated by the normalised offset. Ordering matters: translate is in
     * the already-scaled space so a 0.5 offset always means half the base
     * width, independent of how large the layer is.
     */
    private fun buildMatrix(
        base: Bitmap,
        layer: ImageLayer,
        bmp: Bitmap,
        m: Matrix
    ) {
        val targetWidth = base.width * layer.scale
        val scale = targetWidth / bmp.width.toFloat()

        val centreX = base.width * 0.5f + base.width * layer.offsetX
        val centreY = base.height * 0.5f + base.height * layer.offsetY

        m.reset()
        // Scale about the layer's own centre, then move that centre into place.
        m.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        m.postScale(scale, scale)
        m.postRotate(layer.rotationDegrees)
        m.postTranslate(centreX, centreY)
    }

    /**
     * Resets a layer back to a centred, half-size placement.
     */
    fun resetTransform(layer: ImageLayer): ImageLayer = layer.copy(
        offsetX = 0f,
        offsetY = 0f,
        scale = ImageLayer.defaultScale(),
        rotationDegrees = 0f,
        opacity = 1f
    )
}
