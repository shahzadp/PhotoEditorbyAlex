package com.alex.photoeditor.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Output container formats supported by the exporter. */
enum class ExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String
) {
    JPEG("JPEG", "jpg", "image/jpeg"),
    PNG("PNG", "png", "image/png"),
    WEBP("WebP", "webp", "image/webp")
}

/** Everything the user can configure in the export sheet. */
data class ExportSettings(
    val format: ExportFormat = ExportFormat.JPEG,
    val quality: Int = 92,
    val maxDimension: Int? = null,
    val stripMetadata: Boolean = true,
    val fileName: String? = null
)

/**
 * Writes the final image to shared storage and/or hands it to another app.
 *
 * Uses MediaStore on API 29+ so no storage permission is needed, and falls
 * back to the app's own directory on older releases.
 */
class Exporter(private val context: Context) {

    /** Renders, encodes and saves a copy into the device gallery. */
    suspend fun saveToGallery(
        bitmap: Bitmap,
        settings: ExportSettings
    ): Uri? = withContext(Dispatchers.IO) {
        val scaled = settings.maxDimension?.let { scaleToFit(bitmap, it) } ?: bitmap
        val name = buildFileName(settings)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(name, scaled, settings)
            } else {
                saveLegacy(name, scaled, settings)
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /** Writes a full-quality copy into app-private storage (for "backup"). */
    suspend fun saveToAppStorage(bitmap: Bitmap, settings: ExportSettings): File? =
        withContext(Dispatchers.IO) {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "exports")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, buildFileName(settings))
            val ok = FileOutputStream(file).use { compress(bitmap, settings, it) }
            if (ok) file else null
        }

    /** Encodes to a temp file and returns a shareable content:// Uri. */
    suspend fun createShareUri(bitmap: Bitmap, settings: ExportSettings): Uri? =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, buildFileName(settings))
            val ok = FileOutputStream(file).use { compress(bitmap, settings, it) }
            if (!ok) return@withContext null

            val authority = "${context.packageName}.fileprovider"
            androidx.core.content.FileProvider.getUriForFile(context, authority, file)
        }

    /** Builds a share intent for the given image. */
    fun shareIntent(uri: Uri, format: ExportFormat): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share edited photo")
    }

    // ------------------------------------------------------------ internals

    private fun saveViaMediaStore(
        name: String,
        bitmap: Bitmap,
        settings: ExportSettings
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, settings.format.mimeType)
            // Deliberately omit DATE_TAKEN/LOCATION so stripped metadata stays
            // stripped even after the system re-scans the file.
            if (settings.stripMetadata) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        // Encode straight into the resolver's stream. Building a ByteArray
        // first meant holding the whole encoded image in memory and copying it
        // in one more go, which for a large PNG was tens of megabytes of extra
        // work and allocation.
        val written = resolver.openOutputStream(uri)?.use {
            compress(bitmap, settings, it)
        } ?: false

        if (!written) {
            // Do not leave a zero-byte pending row behind.
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(name: String, bitmap: Bitmap, settings: ExportSettings): Uri? {
        val dir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, name)
        val written = FileOutputStream(file).use { compress(bitmap, settings, it) }
        if (!written) return null

        // Make it visible to the gallery immediately.
        MediaStore.Images.Media.insertImage(
            context.contentResolver, file.absolutePath, name, "Edited photo"
        )
        return Uri.fromFile(file)
    }

    /**
     * Compresses [bitmap] directly into [out].
     *
     * Returning the success flag rather than a byte array keeps the encoded
     * payload out of the heap entirely on the gallery and share paths.
     */
    private fun compress(
        bitmap: Bitmap,
        settings: ExportSettings,
        out: java.io.OutputStream
    ): Boolean {
        val quality = settings.quality.coerceIn(1, 100)
        return when (settings.format) {
            ExportFormat.JPEG -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            ExportFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            ExportFormat.WEBP ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
                } else {
                    @Suppress("DEPRECATION")
                    bitmap.compress(Bitmap.CompressFormat.WEBP, quality, out)
                }
        }
    }

    private fun scaleToFit(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun buildFileName(settings: ExportSettings): String {
        val base = settings.fileName
            ?.takeIf { it.isNotBlank() }
            ?: "PhotoEditor_${System.currentTimeMillis()}"
        val cleaned = base.substringBeforeLast('.').ifBlank { "PhotoEditor" }
        return "$cleaned.${settings.format.extension}"
    }
}
