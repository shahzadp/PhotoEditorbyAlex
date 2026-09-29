package com.alex.photoeditor.core

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a set of taps into an alpha cutout.
 *
 * Kept out of [PipelineRenderer] so the renderer's hot path stays free of
 * segmentation concerns: this runs the model, caches the resulting mask, and
 * the renderer only ever sees a bitmap.
 */
class CutoutComposer(
    private val engine: TapSelectEngine
) {

    /**
     * Cached mask for the current anchor set.
     *
     * Recomputing the mask on every slider or crop change would re-run the
     * model each time, so the result is memoised until the taps change.
     */
    private var cachedKey: String? = null
    private var cachedMask: ConfidenceMask? = null
    private var cachedWidth = 0
    private var cachedHeight = 0

    /** Resolution the cached mask was computed for. */
    private var modelWidth = 0
    private var modelHeight = 0

    /**
     * Precomputed 8-bit alpha field, at *mask* resolution.
     *
     * The feathered threshold used to be re-evaluated per output pixel on
     * every render, and the result written into a freshly allocated
     * full-resolution bitmap that the caller immediately copied back out. That
     * meant every slider frame re-did the smoothstep maths for every pixel and
     * allocated two full-size buffers. The alpha depends only on the mask and
     * the threshold/feather/keep settings, so it is cached at mask
     * resolution and applying it becomes a nearest-neighbour lookup.
     */
    private var cachedAlphaKey: String? = null
    private var cachedAlpha: IntArray? = null
    private var cachedAlphaWidth = 0
    private var cachedAlphaHeight = 0

    /**
     * Applies the current cutout straight into [px].
     *
     * Mutates the caller's buffer in place instead of returning a bitmap, so
     * no intermediate image is allocated on the render path.
     */
    suspend fun applyToPixels(
        px: IntArray,
        width: Int,
        height: Int,
        state: CutoutState
    ) = withContext(Dispatchers.Default) {
        if (!state.isActive) return@withContext
        val mask = ensureMask(px, width, height, state) ?: return@withContext
        val alpha = ensureAlpha(mask, state) ?: return@withContext
        Cutout.applyAlpha(px, width, height, alpha, cachedAlphaWidth, cachedAlphaHeight)
    }

    /**
     * Returns a cutout bitmap for [state], or null if the model is unavailable.
     *
     * The mask is always computed at the model's working resolution and then
     * scaled to the requested size, so a tap selection made on a 512px preview
     * still yields a clean edge on a 4096px export.
     */
    suspend fun cutout(
        source: Bitmap,
        state: CutoutState
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (!state.isActive) return@withContext null

        // Drive the cache from the source's own pixels so the mask resolution
        // matches what applyToPixels will look up against.
        val px = IntArray(source.width * source.height)
        source.getPixels(px, 0, source.width, 0, 0, source.width, source.height)

        val mask = ensureMask(px, source.width, source.height, state) ?: return@withContext null
        val alpha = ensureAlpha(mask, state) ?: return@withContext null
        Cutout.applyAlpha(px, source.width, source.height, alpha, cachedAlphaWidth, cachedAlphaHeight)

        Bitmap.createBitmap(px, source.width, source.height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Ensures a confidence mask exists for [state], running the model only when
     * the taps or the source size have changed.
     *
     * [px] is only used for its dimensions; the model's own downscaled copy is
     * what actually gets segmented.
     */
    private suspend fun ensureMask(
        px: IntArray,
        width: Int,
        height: Int,
        state: CutoutState
    ): ConfidenceMask? {
        engine.initialise()
        if (!engine.isReady) {
            Log.w(TAG, "Cutout requested but engine not ready")
            return null
        }

        // The model works in a fixed-resolution space derived from the
        // uncropped source, so crop changes reuse the same cache entry.
        val key = anchorKey(state)
        val needResize = modelWidth != width || modelHeight != height

        if (key != cachedKey || needResize || cachedMask == null) {
            Log.i(TAG, "Rebuilding mask: ${state.anchors.size} anchor(s), cacheHit=${key == cachedKey}")
            val working = scaledForModel(px, width, height)
            engine.setImage(working)
            modelWidth = width
            modelHeight = height

            val mask = buildMask(state)
            if (mask == null) {
                Log.w(TAG, "buildMask returned null")
                cachedKey = null
                cachedMask = null
                return null
            }
            cachedKey = key
            cachedMask = mask
            // The mask changed, so any cached alpha is stale.
            cachedAlphaKey = null
            cachedAlpha = null
        }

        return cachedMask
    }

    /**
     * Ensures the feathered alpha field for [mask] and the current threshold
     * settings is available, rebuilding it only when something it depends on
     * changed.
     */
    private fun ensureAlpha(mask: ConfidenceMask, state: CutoutState): IntArray? {
        val key = alphaKey(state)
        if (key == cachedAlphaKey && cachedAlpha != null) return cachedAlpha

        val field = Cutout.alphaField(
            mask = mask,
            threshold = state.threshold,
            feather = state.feather,
            keepSubject = state.keepSubject
        ) ?: return null

        cachedAlphaKey = key
        cachedAlpha = field
        cachedAlphaWidth = mask.width
        cachedAlphaHeight = mask.height
        return field
    }

    /**
     * Combines every tap into one mask.
     *
     * A tap selects the object under that point. Multiple taps union their
     * confidence so selecting a person and then their bag keeps both.
     */
    private suspend fun buildMask(state: CutoutState): ConfidenceMask? {
        var accumulated: FloatArray? = null
        var width = 0
        var height = 0

        for (anchor in state.anchors) {
            val result = engine.segment(anchor.x, anchor.y) ?: continue
            if (width == 0) {
                width = result.width
                height = result.height
                accumulated = FloatArray(width * height)
            }

            val acc = accumulated ?: continue
            // Union: keep the strongest confidence across taps.
            for (i in acc.indices) {
                if (result.values[i] > acc[i]) acc[i] = result.values[i]
            }
        }

        val values = accumulated ?: return null
        return ConfidenceMask(values, width, height)
    }

    /**
     * Downscales the source to the model's working resolution.
     *
     * MagicTouch expects a modest input; running it on a full 4096px photo
     * costs memory and seconds for no accuracy gain, since the mask is
     * upscaled to the render size anyway.
     */
    private fun scaledForModel(px: IntArray, width: Int, height: Int): Bitmap {
        val longEdge = max(width, height)
        if (longEdge <= TapSelectEngine.MODEL_EDGE) {
            return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
        }

        val ratio = TapSelectEngine.MODEL_EDGE.toFloat() / longEdge.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888),
            (width * ratio).roundToInt().coerceAtLeast(1),
            (height * ratio).roundToInt().coerceAtLeast(1),
            true
        )
        return if (scaled.isMutable) {
            scaled
        } else {
            scaled.copy(Bitmap.Config.ARGB_8888, true)
        }
    }

    /**
     * A stable identity for the current tap set, used as a cache key.
     *
     * Only the taps matter: the mask is threshold-independent (it is raw
     * confidence), so moving the threshold or flipping keep/remove reuses the
     * cached mask and just re-derives the alpha.
     */
    private fun anchorKey(state: CutoutState): String =
        buildString {
            state.anchors.forEach { append(it.x).append(',').append(it.y).append(';') }
        }

    /**
     * Identity for the derived alpha field, which additionally depends on the
     * threshold, feather and keep/remove sense.
     */
    private fun alphaKey(state: CutoutState): String =
        "${anchorKey(state)}|${state.threshold}|${state.feather}|${state.keepSubject}"

    /** Drops the cached mask, e.g. when a new image is opened. */
    fun invalidate() {
        Log.i(TAG, "Cache invalidated")
        cachedKey = null
        cachedMask = null
        cachedAlphaKey = null
        cachedAlpha = null
        modelWidth = 0
        modelHeight = 0
    }

    fun release() {
        invalidate()
        engine.release()
    }

    private companion object {
        const val TAG = "CutoutComposer"
    }
}
