package com.alex.photoeditor.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A crop rectangle in source-image pixel coordinates. */
data class CropRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(1)
    val height: Int get() = (bottom - top).coerceAtLeast(1)
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()

    /** Clamps into bounds and guarantees a non-degenerate rectangle. */
    fun clampTo(width: Int, height: Int): CropRect {
        val l = left.coerceIn(0, width - 1)
        val t = top.coerceIn(0, height - 1)
        val r = right.coerceIn(l + 1, width)
        val b = bottom.coerceIn(t + 1, height)
        return CropRect(l, t, r, b)
    }

    companion object {
        fun full(width: Int, height: Int) = CropRect(0, 0, width, height)
    }
}

/** Common crop aspect ratios offered in the UI. */
enum class AspectPreset(val label: String, val ratio: Float?) {
    FREE("Free", null),
    SQUARE("1:1", 1f),
    PORTRAIT_4_5("4:5", 4f / 5f),
    PORTRAIT_2_3("2:3", 2f / 3f),
    LANDSCAPE_3_2("3:2", 3f / 2f),
    LANDSCAPE_16_9("16:9", 16f / 9f),
    CINEMASCOPE("2.39:1", 2.39f)
}

enum class FlipAxis { HORIZONTAL, VERTICAL }

/**
 * Non-destructive geometry operations.
 *
 * Each function returns a new bitmap; nothing mutates the source, so the
 * editor can always re-derive a preview from the original.
 */
class GeometryEngine {

    suspend fun crop(source: Bitmap, rect: CropRect): Bitmap =
        withContext(Dispatchers.Default) {
            val safe = rect.clampTo(source.width, source.height)
            if (safe.left == 0 && safe.top == 0 &&
                safe.width == source.width && safe.height == source.height
            ) return@withContext source

            val out = Bitmap.createBitmap(safe.width, safe.height, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(
                source,
                Rect(safe.left, safe.top, safe.right, safe.bottom),
                Rect(0, 0, safe.width, safe.height),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            )
            out
        }

    /** Rotates by 90/180/270 degrees, keeping pixels lossless. */
    suspend fun rotate(source: Bitmap, degrees: Int): Bitmap =
        withContext(Dispatchers.Default) {
            val norm = ((degrees % 360) + 360) % 360
            if (norm == 0) return@withContext source

            val matrix = Matrix().apply { postRotate(norm.toFloat()) }
            val out = Bitmap.createBitmap(
                source, 0, 0, source.width, source.height, matrix, true
            )
            if (out === source) source.copy(Bitmap.Config.ARGB_8888, true) else out
        }

    /** Free rotation in 0.1 degree steps, for the straighten tool. */
    suspend fun straighten(source: Bitmap, degrees: Float): Bitmap =
        withContext(Dispatchers.Default) {
            if (kotlin.math.abs(degrees) < 0.05f) return@withContext source

            val matrix = Matrix().apply { postRotate(degrees) }
            val out = Bitmap.createBitmap(
                source, 0, 0, source.width, source.height, matrix, true
            )
            if (out === source) source.copy(Bitmap.Config.ARGB_8888, true) else out
        }

    suspend fun flip(source: Bitmap, axis: FlipAxis): Bitmap =
        withContext(Dispatchers.Default) {
            val matrix = Matrix().apply {
                when (axis) {
                    FlipAxis.HORIZONTAL -> postScale(-1f, 1f, source.width / 2f, source.height / 2f)
                    FlipAxis.VERTICAL -> postScale(1f, -1f, source.width / 2f, source.height / 2f)
                }
            }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }

    suspend fun resize(source: Bitmap, width: Int, height: Int): Bitmap =
        withContext(Dispatchers.Default) {
            val w = width.coerceAtLeast(1)
            val h = height.coerceAtLeast(1)
            if (w == source.width && h == source.height) return@withContext source
            Bitmap.createScaledBitmap(source, w, h, true)
        }

    /**
     * Computes the largest centred crop of the requested aspect ratio, so the
     * UI can snap to 1:1 / 16:9 etc. without letting the user drag freehand.
     */
    fun centeredCropFor(source: Bitmap, ratio: Float): CropRect {
        val w = source.width
        val h = source.height
        val current = w.toFloat() / h.toFloat()

        return if (current > ratio) {
            // too wide: trim the sides
            val newW = (h * ratio).roundToInt().coerceIn(1, w)
            val left = (w - newW) / 2
            CropRect(left, 0, left + newW, h)
        } else {
            // too tall: trim top/bottom
            val newH = (w / ratio).roundToInt().coerceIn(1, h)
            val top = (h - newH) / 2
            CropRect(0, top, w, top + newH)
        }
    }

    /**
     * Expands the canvas so the image sits on a transparent or coloured
     * background — the "expand canvas" tool.
     */
    suspend fun expandCanvas(
        source: Bitmap,
        newWidth: Int,
        newHeight: Int,
        background: Int = Color.TRANSPARENT
    ): Bitmap =
        withContext(Dispatchers.Default) {
            val w = newWidth.coerceAtLeast(source.width)
            val h = newHeight.coerceAtLeast(source.height)
            if (w == source.width && h == source.height) return@withContext source

            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(background)
            val left = (w - source.width) / 2
            val top = (h - source.height) / 2
            canvas.drawBitmap(
                source, left.toFloat(), top.toFloat(),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            )
            out
        }

    /**
     * Straightens while cropping away the transparent corners, so the result
     * stays a tight rectangle instead of gaining empty wedges.
     */
    suspend fun straightenAndTrim(source: Bitmap, degrees: Float): Bitmap =
        withContext(Dispatchers.Default) {
            if (kotlin.math.abs(degrees) < 0.05f) return@withContext source

            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(
                source, 0, 0, source.width, source.height, matrix, true
            )
            val trimmed = trimTransparentEdges(rotated)
            if (rotated !== source && rotated !== trimmed) rotated.recycle()
            trimmed
        }

    /** Finds the bounding box of non-transparent pixels. */
    private fun trimTransparentEdges(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val row = IntArray(w)

        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1

        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            var rowMin = -1
            var rowMax = -1
            for (x in 0 until w) {
                if ((row[x] ushr 24) != 0) {
                    if (rowMin < 0) rowMin = x
                    rowMax = x
                }
            }
            if (rowMin >= 0) {
                if (rowMin < minX) minX = rowMin
                if (rowMax > maxX) maxX = rowMax
                if (y < minY) minY = y
                maxY = y
            }
        }

        if (maxX < 0 || minX == 0 && minY == 0 && maxX == w - 1 && maxY == h - 1) {
            return src
        }
        val rect = Rect(minX.coerceAtLeast(0), minY.coerceAtLeast(0),
            (maxX + 1).coerceAtMost(w), (maxY + 1).coerceAtMost(h))
        val out = Bitmap.createBitmap(
            rect.width(), rect.height(), Bitmap.Config.ARGB_8888
        )
        Canvas(out).drawBitmap(
            src, rect, Rect(0, 0, rect.width(), rect.height()),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        return out
    }
}
