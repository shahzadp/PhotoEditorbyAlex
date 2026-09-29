package com.alex.photoeditor.core

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders the full edit stack with a single reusable pixel buffer.
 *
 * Why this exists
 * ---------------
 * The naive pipeline runs each stage as `Bitmap -> IntArray -> new Bitmap`.
 * With geometry, a filter, colour grade and adjustments that is four
 * full-resolution arrays alive at once, and on a 4096px photo a single
 * `IntArray` is 67 MB. That causes GC churn and jank while dragging.
 *
 * This renderer instead:
 *  - allocates **one** [IntArray] for the whole pass, reused between frames,
 *  - runs every enabled stage **in place** on it.
 *
 * Only genuinely size-changing operations (rotate, crop, resize) allocate an
 * extra bitmap, and those are not on the slider-drag path.
 *
 * The output bitmap is always freshly allocated rather than pooled. Pooling
 * was tried and reverted: [render] legitimately hands back the *input* bitmap
 * when no pixel stage applies, and [downscale] returns its source unchanged for
 * small images, so an unowned bitmap eventually became the write target and
 * `setPixels` threw `IllegalStateException`. Ownership here is not clean enough
 * to recycle safely, so the GC stays in charge of bitmap lifetime.
 */
class PipelineRenderer(
    private val adjustmentEngine: AdjustmentEngine = AdjustmentEngine(),
    private val filterEngine: FilterEngine = FilterEngine(),
    private val geometryEngine: GeometryEngine = GeometryEngine(),
    private val colorGradeEngine: ColorGradeEngine = ColorGradeEngine(),
    /** Optional; when absent the "tap to select" tool is disabled. */
    private val cutoutComposer: CutoutComposer? = null,
    /**
     * Supplies layer bitmaps.
     *
     * Declared as a lookup rather than the concrete [LayerStore] so the
     * renderer stays free of caching concerns and can be exercised without one.
     */
    private val layerBitmaps: ((ImageLayer) -> Bitmap?)? = null
) {

    /** Reused across renders so slider drags do not churn the heap. */
    private var scratch: IntArray = IntArray(0)

    /**
     * Separate buffer for the cutout pass.
     *
     * The cutout resolves into its own bitmap before the layers are stacked,
     * while `scratch` is still holding the colour stage's working copy from a
     * previous frame, so the two cannot share one array.
     */
    private var cutoutScratch: IntArray = IntArray(0)

    /**
     * Renders [base] according to [state].
     *
     * @param previewSize downscale the working image to this longest edge, or
     *        null to render at full resolution (export).
     */
    suspend fun render(
        base: Bitmap,
        state: EditState,
        previewSize: Int?
    ): Bitmap? = withContext(Dispatchers.Default) {
        // ---- geometry on the bitmap itself (size changes need a real bitmap)
        var img = base
        if (previewSize != null) img = downscale(base, previewSize)

        val rotated = state.rotationSteps % 4 != 0
        val straightened = state.straightenDegrees != 0f
        val flipped = state.flipH || state.flipV

        if (rotated) img = geometryEngine.rotate(img, state.rotationSteps * 90)
        if (straightened) img = geometryEngine.straightenAndTrim(img, state.straightenDegrees)
        if (state.flipH) img = geometryEngine.flip(img, FlipAxis.HORIZONTAL)
        if (state.flipV) img = geometryEngine.flip(img, FlipAxis.VERTICAL)

        val crop = state.crop?.let { rect ->
            val fullW = geometryWidth(base, state)
            if (fullW > 0 && img.width != fullW) {
                // Scale into preview space FIRST, then clamp. Clamping a
                // full-resolution rect against preview dimensions would chop it
                // to the top-left of the preview before the scale factor was
                // ever applied, so a large crop collapsed to a small square in
                // the wrong place.
                val k = img.width.toFloat() / fullW.toFloat()
                CropRect(
                    (rect.left * k).toInt(),
                    (rect.top * k).toInt(),
                    (rect.right * k).toInt(),
                    (rect.bottom * k).toInt()
                ).clampTo(img.width, img.height)
            } else {
                rect.clampTo(img.width, img.height)
            }
        }

        val resizeW = state.resizeWidth
        val resizeH = state.resizeHeight
        val resizing = resizeW != null && resizeH != null
        val cuttingOut = state.cutout.isActive
        val hasLayers = state.layers.any { it.visible && it.opacity > 0f }

        // Whether any stage will touch a pixel at all. The cutout is excluded
        // because it is resolved into its own bitmap below, before the layers
        // are stacked, rather than in the shared buffer.
        val needsColourWork = state.filterId != "original" ||
            !state.adjustments.isNeutral ||
            !state.colorGrade.isNeutral

        if (!needsColourWork && !cuttingOut && crop == null && !resizing && !hasLayers) {
            return@withContext img
        }

        if (crop != null && (crop.width != img.width || crop.height != img.height)) {
            img = geometryEngine.crop(img, crop)
        }
        if (resizeW != null && resizeH != null) {
            img = geometryEngine.resize(img, resizeW, resizeH)
        }

        // ---- cutout, then stack the layers, then the colour stages
        //
        // Order matters twice over:
        //
        // 1. The cutout mask is anchored to the *base* photo - the user tapped
        //    an object on it - so it has to be resolved before any layer is
        //    drawn. Compositing first would let the mask punch transparent
        //    holes straight through the stacked images.
        //
        // 2. The layers go in *before* the colour stages. Compositing after
        //    them, as this used to, meant a filter or an exposure slider only
        //    ever touched the base photo and every stacked image stayed raw,
        //    so adding a photo visibly broke the look of the edit.
        //
        // Layer *opacity* is honoured at draw time, so a half-transparent layer
        // blends with what is beneath it before grading, rather than being
        // graded independently and then blended.
        if (cuttingOut) {
            img = applyCutout(img, state.cutout)
        }

        if (hasLayers && layerBitmaps != null) {
            img = LayerComposer.compose(img, state.layers, layerBitmaps)
        }

        if (!needsColourWork) {
            return@withContext img
        }

        // ---- single shared buffer for every pixel stage
        val needed = img.width * img.height
        if (scratch.size < needed) scratch = IntArray(needed)
        val px = scratch
        img.getPixels(px, 0, img.width, 0, 0, img.width, img.height)

        // Colour stages run in place, in a fixed order so results are
        // deterministic: filter -> colour grade -> tonal adjustments.
        FilterLibrary.byId[state.filterId]?.let { filter ->
            if (filter.id != "original") filterEngine.applyToPixels(px, img.width, img.height, filter)
        }
        if (!state.colorGrade.isNeutral) {
            colorGradeEngine.applyInPlace(px, state.colorGrade)
        }
        if (!state.adjustments.isNeutral) {
            adjustmentEngine.applyToPixels(px, img.width, img.height, state.adjustments)
        }

        // ---- write the result out
        //
        // The output bitmap is always freshly allocated. An earlier version
        // pooled it, which crashed with `IllegalStateException` at
        // `Bitmap.setPixels`: `render` legitimately returns the *input*
        // bitmap when no pixel stage applies, and `downscale` returns its
        // source unchanged for small images, so the original photo got
        // recycled into the pool and later handed back as the write target.
        val out = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, img.width, 0, 0, img.width, img.height)
        out
    }

    /**
     * Resolves the cutout into its own bitmap.
     *
     * Kept separate from the shared pixel buffer because the result has to
     * exist as a bitmap *before* the layers are stacked on top of it, whereas
     * the colour stages all run in place afterwards.
     */
    private suspend fun applyCutout(img: Bitmap, state: CutoutState): Bitmap {
        val composer = cutoutComposer ?: return img
        val w = img.width
        val h = img.height

        val needed = w * h
        if (cutoutScratch.size < needed) cutoutScratch = IntArray(needed)
        val px = cutoutScratch
        img.getPixels(px, 0, w, 0, 0, w, h)

        composer.applyToPixels(px, w, h, state)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun downscale(src: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = maxOf(src.width, src.height)
        if (longEdge <= maxEdge) return src
        val ratio = maxEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            src,
            (src.width * ratio).toInt().coerceAtLeast(1),
            (src.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    /** Full-resolution width of the post-geometry frame. */
    private fun geometryWidth(base: Bitmap, state: EditState): Int {
        val swapped = state.rotationSteps % 2 != 0
        val w = if (swapped) base.height else base.width
        if (state.straightenDegrees == 0f) return w

        val rad = Math.toRadians(state.straightenDegrees.toDouble())
        val c = kotlin.math.abs(kotlin.math.cos(rad))
        val s = kotlin.math.abs(kotlin.math.sin(rad))
        val other = if (swapped) base.width else base.height
        return (w * c + other * s).toInt().coerceAtLeast(1)
    }

    /** Drops the pixel scratch buffer; called when an image is closed. */
    fun release() {
        scratch = IntArray(0)
        cutoutScratch = IntArray(0)
    }
}
