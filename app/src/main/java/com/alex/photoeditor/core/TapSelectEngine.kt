package com.alex.photoeditor.core

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenterOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One-click object selection, backed by MediaPipe's MagicTouch model.
 *
 * The user taps a point, the model returns a confidence mask for the object
 * under that point, and [Cutout] turns that mask into an alpha cutout.
 *
 * Everything runs offline: the model ships in `app/src/main/assets` and the
 * app declares no INTERNET permission.
 *
 * API note: this targets `tasks-vision` 1.0.0, whose [InteractiveSegmenter]
 * takes a list of [Stroke]s and returns the mask as an [MPImage]. The 0.10.x
 * line used a different `RegionOfInterest` API and could not read the current
 * `.task` format, which is a task bundle (a ZIP) rather than a flatbuffer.
 */
class TapSelectEngine(
    private val context: Context,
    private val modelAssetPath: String = MODEL_ASSET
) {

    private var segmenter: InteractiveSegmenter? = null
    private var inputImage: MPImage? = null

    /** Set when the engine is usable; false if the model failed to load. */
    val isReady: Boolean get() = segmenter != null

    /**
     * Creates the segmenter. Must be called off the main thread; the GPU
     * delegate in particular has to be created and used on the same thread.
     */
    suspend fun initialise() = withContext(Dispatchers.Default) {
        if (segmenter != null) return@withContext

        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelAssetPath)
                .build()

            val options = InteractiveSegmenterOptions.builder()
                .setBaseOptions(baseOptions)
                .build()

            segmenter = InteractiveSegmenter.createFromOptions(context, options)
            Log.i(TAG, "Segmenter initialised OK")
        } catch (e: Throwable) {
            // Logged at error with the full cause on purpose. Every failure in
            // this engine is swallowed so a missing or unloadable model
            // degrades to "cutout unavailable" instead of taking the editor
            // down - but that same swallowing is what made an obfuscation
            // regression invisible, because a stripped protobuf class
            // produced exactly the same silent nothing as a corrupt file.
            Log.e(TAG, "Failed to initialise interactive segmenter", e)
            segmenter = null
        }
    }

    /**
     * Points the model at the image to segment.
     *
     * The model reads from the bitmap on every call, so nothing has to stay
     * alive beyond this; the image is held as an [MPImage] wrapper and passed
     * to `setImage` as the 1.0.0 API requires.
     */
    suspend fun setImage(bitmap: Bitmap) = withContext(Dispatchers.Default) {
        val active = segmenter ?: run {
            Log.w(TAG, "setImage called before initialise")
            return@withContext
        }
        try {
            // The model requires a mutable bitmap: it reuses the buffer for
            // ROI extraction across taps.
            val mutable = if (bitmap.isMutable) {
                bitmap
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, true)
            }
            val mpImage = BitmapImageBuilder(mutable).build()
            active.setImage(mpImage)
            inputImage = mpImage
            Log.i(TAG, "Input image set: ${mutable.width}x${mutable.height}")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to set segmenter input image", e)
            inputImage = null
        }
    }

    /**
     * Segments the object at a normalised point.
     *
     * @return a confidence mask, or null if the model is not ready or
     *         produced nothing usable.
     */
    suspend fun segment(normalisedX: Float, normalisedY: Float): ConfidenceMask? =
        withContext(Dispatchers.Default) {
            val active = segmenter ?: run {
                Log.w(TAG, "segment() called but segmenter is null")
                return@withContext null
            }
            if (inputImage == null) {
                Log.w(TAG, "segment() called but no input image")
                return@withContext null
            }

            try {
                val x = normalisedX.coerceIn(0f, 1f)
                val y = normalisedY.coerceIn(0f, 1f)

                val stroke = Stroke.builder()
                    .setBrushMode(Stroke.BrushMode.POSITIVE)
                    .setPoints(listOf(NormalizedKeypoint.create(x, y)))
                    .setCompleted(true)
                    .build()

                val mask = active.segment(listOf(stroke))
                Log.i(TAG, "segment($x, $y) -> mask=${mask?.width}x${mask?.height}")
                if (mask == null) return@withContext null

                val w = mask.width
                val h = mask.height
                val total = w * h
                if (total <= 0) return@withContext null

                val buffer = ByteBufferExtractor.extract(mask).asFloatBuffer()
                val values = FloatArray(total)
                buffer.rewind()
                buffer.get(values, 0, total)

                // Report the confidence spread. A flat mask means the tap did
                // not land on anything the model could separate.
                var minV = Float.MAX_VALUE
                var maxV = -Float.MAX_VALUE
                var above = 0
                for (v in values) {
                    if (v < minV) minV = v
                    if (v > maxV) maxV = v
                    if (v >= 0.5f) above++
                }
                Log.i(
                    TAG,
                    "mask ${w}x$h min=$minV max=$maxV " +
                        "above50%=$above (${100.0f * above / total}%)"
                )

                ConfidenceMask(values, w, h)
            } catch (e: Throwable) {
                Log.e(TAG, "Segmentation failed", e)
                null
            }
        }

    fun release() {
        try {
            segmenter?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to close segmenter", e)
        }
        segmenter = null
        inputImage = null
    }

    companion object {
        private const val TAG = "TapSelectEngine"
        const val MODEL_ASSET = "interactive_segmentation.task"

        /**
         * Model input edge. MagicTouch is trained at 256px; feeding it a full
         * 4096px photo costs memory and time for no extra accuracy.
         */
        const val MODEL_EDGE = 512
    }
}

/** A raw per-pixel confidence mask in [0..1], as returned by the model. */
class ConfidenceMask(
    val values: FloatArray,
    val width: Int,
    val height: Int
)

/**
 * Applies a cutout to a bitmap.
 *
 * The mask is feathered around the threshold because a hard 0/1 edge produces
 * a jagged, obviously-cut out outline. [feather] controls the ramp width in
 * mask space, so the softness scales with the output resolution.
 */
object Cutout {

    /**
     * Reduces a confidence mask to an 8-bit alpha field at *mask* resolution.
     *
     * This is the expensive part of the cutout — a smoothstep across the
     * feather band per pixel — and it depends only on the mask plus the
     * threshold settings, never on the render resolution. Computing it once
     * and caching it means the per-frame work drops to a table lookup.
     *
     * Returns null only if the mask is empty.
     */
    fun alphaField(
        mask: ConfidenceMask,
        threshold: Float = 0.5f,
        feather: Float = 0.12f,
        keepSubject: Boolean = true
    ): IntArray? {
        val w = mask.width
        val h = mask.height
        if (w <= 0 || h <= 0) return null

        val out = IntArray(w * h)
        val lower = (threshold - feather).coerceIn(0f, 1f)
        val upper = (threshold + feather).coerceIn(0f, 1f)
        val span = max(1e-4f, upper - lower)

        for (i in out.indices) {
            val raw = mask.values[i]
            // Inverting turns "keep the subject" into "keep everything else",
            // which is what the remove-background toggle needs.
            val c = if (keepSubject) raw else 1f - raw

            // Smoothstep across the feather band, so the edge fades in and out
            // instead of stepping.
            val t = when {
                c <= lower -> 0f
                c >= upper -> 1f
                else -> {
                    val s = (c - lower) / span
                    s * s * (3f - 2f * s)
                }
            }
            out[i] = (t * 255f).roundToInt().coerceIn(0, 255)
        }
        return out
    }

    /**
     * Multiplies a cached alpha field into [px] in place.
     *
     * The field is at mask resolution and is sampled nearest-neighbour, which
     * is the same sampling the old per-pixel path used. Alpha is multiplied
     * rather than replaced so an already-transparent pixel stays transparent.
     */
    fun applyAlpha(
        px: IntArray,
        width: Int,
        height: Int,
        alphaField: IntArray,
        fieldWidth: Int,
        fieldHeight: Int
    ) {
        if (width <= 0 || height <= 0 || fieldWidth <= 0 || fieldHeight <= 0) return

        val scaleX = fieldWidth.toFloat() / width.toFloat()
        val scaleY = fieldHeight.toFloat() / height.toFloat()

        for (y in 0 until height) {
            val srcRow = min(fieldHeight - 1, (y * scaleY).toInt())
            val rowBase = srcRow * fieldWidth
            val outRow = y * width

            for (x in 0 until width) {
                val srcCol = min(fieldWidth - 1, (x * scaleX).toInt())
                val alpha = alphaField[rowBase + srcCol]
                val existing = px[outRow + x]
                val srcAlpha = (existing ushr 24) and 0xFF
                px[outRow + x] = ((srcAlpha * alpha) / 255 shl 24) or (existing and 0x00FFFFFF)
            }
        }
    }

    fun apply(
        source: Bitmap,
        mask: ConfidenceMask,
        threshold: Float = 0.5f,
        feather: Float = 0.12f,
        keepSubject: Boolean = true
    ): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return source

        val field = alphaField(mask, threshold, feather, keepSubject) ?: return source
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        applyAlpha(pixels, w, h, field, mask.width, mask.height)
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
