package com.alex.photoeditor.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Result of loading a user-picked image. */
data class LoadedImage(
    val bitmap: Bitmap,
    val info: ImageInfo
)

/** Non-editing metadata surfaced in the Info panel. */
data class ImageInfo(
    val displayName: String,
    val widthPx: Int,
    val heightPx: Int,
    val sizeBytes: Long,
    val mimeType: String,
    val cameraMake: String?,
    val cameraModel: String?,
    val dateTaken: String?,
    val gpsPresent: Boolean,
    val gpsLat: Double?,
    val gpsLon: Double?,
    val orientationDegrees: Int
) {
    val aspectRatio: Float
        get() = if (heightPx == 0) 1f else widthPx.toFloat() / heightPx.toFloat()

    val megapixels: Float
        get() = (widthPx.toLong() * heightPx) / 1_000_000f
}

/**
 * Decodes images off the main thread, downsampling huge photos to a sane working
 * size and normalising EXIF rotation so the editor always sees an upright bitmap.
 */
object ImageLoader {

    /** Longest edge, in pixels, that we keep for interactive editing. */
    private const val MAX_WORKING_EDGE = 4096

    suspend fun load(context: Context, uri: Uri): LoadedImage {
        // Copy out of the provider while the grant is still valid, then decode
        // from our own file. See ImageStore for why this indirection matters.
        val file = ImageStore.import(context, uri)
        return loadFromFile(context, file, ImageStore.displayName(uri))
    }

    /**
     * Decodes a file this app owns. No content-provider involved, so this
     * cannot fail because of a revoked URI permission.
     */
    suspend fun loadFromFile(
        context: Context,
        file: java.io.File,
        displayName: String
    ): LoadedImage =
        withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw ImageImportException(
                    "\"$displayName\" is not a supported image format."
                )
            }

            val mime = sniffMime(file)
            val exif = readExif(file)

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                ?: throw ImageImportException(
                    "Could not decode \"$displayName\". The file may be corrupted."
                )

            val rotation = exif?.rotationDegrees() ?: 0
            val upright = applyOrientation(decoded, rotation)
            val working = scaleDownToWorkingSize(upright)

            val info = ImageInfo(
                displayName = displayName,
                widthPx = working.width,
                heightPx = working.height,
                sizeBytes = file.length(),
                mimeType = mime,
                cameraMake = exif?.cameraMake(),
                cameraModel = exif?.cameraModel(),
                dateTaken = exif?.dateTaken(),
                gpsPresent = exif?.hasLocation() == true,
                gpsLat = exif?.latLong?.get(0),
                gpsLon = exif?.latLong?.get(1),
                orientationDegrees = rotation
            )

            // No manual recycle: the intermediates are unreachable here, so
            // the GC reclaims them once Compose is done with the final bitmap.
            LoadedImage(working, info)
        }

    /** Decodes a preview from a file this app owns. */
    fun loadPreviewFromFile(
        file: java.io.File,
        maxEdge: Int = 1024
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(
                bounds.outWidth, bounds.outHeight, targetMaxEdge = maxEdge
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        val exif = readExif(file)
        return applyOrientation(decoded, exif?.rotationDegrees() ?: 0)
    }

    private fun calculateSampleSize(
        width: Int,
        height: Int,
        targetMaxEdge: Int = MAX_WORKING_EDGE
    ): Int {
        var sample = 1
        var longEdge = max(width, height)
        while (longEdge / 2 >= targetMaxEdge) {
            longEdge /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleDownToWorkingSize(src: Bitmap): Bitmap {
        val longEdge = max(src.width, src.height)
        if (longEdge <= MAX_WORKING_EDGE) return src

        val ratio = MAX_WORKING_EDGE.toFloat() / longEdge
        val w = max(1, (src.width * ratio).toInt())
        val h = max(1, (src.height * ratio).toInt())
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /**
     * Rotates a bitmap. The source is left untouched — callers own their
     * bitmaps and may still be drawing them, so recycling here is unsafe.
     */
    private fun applyOrientation(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun readExif(file: java.io.File): ExifInterface? = runCatching {
        ExifInterface(file.absolutePath)
    }.getOrNull()

    private fun ExifInterface.rotationDegrees(): Int =
        when (getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    private fun ExifInterface.hasLocation(): Boolean =
        getAttribute(ExifInterface.TAG_GPS_LATITUDE) != null

    private fun ExifInterface.cameraMake(): String? =
        getAttribute(ExifInterface.TAG_MAKE)?.takeIf { it.isNotBlank() }

    private fun ExifInterface.cameraModel(): String? =
        getAttribute(ExifInterface.TAG_MODEL)?.takeIf { it.isNotBlank() }

    /** EXIF stores the capture time as `yyyy:MM:dd HH:mm:ss`; reformat for display. */
    private fun ExifInterface.dateTaken(): String? {
        val raw = getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: getAttribute(ExifInterface.TAG_DATETIME)
            ?: return null
        return runCatching {
            val parsed = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)
            parsed?.let { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(it) }
        }.getOrNull()
    }

    /**
     * Identifies the format from the file's magic bytes.
     *
     * The picker gives us opaque names with no usable extension
     * (`document/image%3A1000042150`), and our private copy has none at all,
     * so the header is the only reliable signal.
     */
    private fun sniffMime(file: java.io.File): String {
        val head = ByteArray(12)
        val read = runCatching {
            java.io.FileInputStream(file).use { it.read(head) }
        }.getOrDefault(-1)
        if (read < 4) return "image/jpeg"

        val b = { i: Int -> head[i].toInt() and 0xFF }
        return when {
            // JPEG: FF D8 FF
            b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF -> "image/jpeg"
            // PNG: 89 50 4E 47
            b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47 -> "image/png"
            // GIF: "GIF8"
            b(0) == 'G'.code && b(1) == 'I'.code && b(2) == 'F'.code -> "image/gif"
            // RIFF....WEBP
            b(0) == 'R'.code && b(1) == 'I'.code && b(2) == 'F'.code &&
                b(8) == 'W'.code && b(9) == 'E'.code &&
                b(10) == 'B'.code && b(11) == 'P'.code -> "image/webp"
            // HEIC / HEIF: ....ftyp
            b(4) == 'f'.code && b(5) == 't'.code &&
                b(6) == 'y'.code && b(7) == 'p'.code -> "image/heif"
            else -> "image/jpeg"
        }
    }
}
