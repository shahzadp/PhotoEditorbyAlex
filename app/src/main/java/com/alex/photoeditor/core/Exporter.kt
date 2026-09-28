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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        val bytes = encode(scaled, settings) ?: return@withContext null

        val name = buildFileName(settings)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(name, bytes, settings)
        } else {
            saveLegacy(name, bytes)
        }.also { _ ->
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /** Writes a full-quality copy into app-private storage (for "backup"). */
    suspend fun saveToAppStorage(bitmap: Bitmap, settings: ExportSettings): File? =
        withContext(Dispatchers.IO) {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "exports")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, buildFileName(settings))
            val bytes = encode(bitmap, settings) ?: return@withContext null
            FileOutputStream(file).use { it.write(bytes) }
            file
        }

    /** Encodes to a temp file and returns a shareable content:// Uri. */
    suspend fun createShareUri(bitmap: Bitmap, settings: ExportSettings): Uri? =
        withContext(Dispatchers.IO) {
            val bytes = encode(bitmap, settings) ?: return@withContext null
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(dir, buildFileName(settings))
            FileOutputStream(file).use { it.write(bytes) }

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
        bytes: ByteArray,
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

        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: return null

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(name: String, bytes: ByteArray): Uri? {
        val dir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, name)
        FileOutputStream(file).use { it.write(bytes) }

        // Make it visible to the gallery immediately.
        MediaStore.Images.Media.insertImage(
            context.contentResolver, file.absolutePath, name, "Edited photo"
        )
        return Uri.fromFile(file)
    }

    private fun encode(bitmap: Bitmap, settings: ExportSettings): ByteArray? {
        val stream = java.io.ByteArrayOutputStream()
        val quality = settings.quality.coerceIn(1, 100)
        val ok = when (settings.format) {
            ExportFormat.JPEG -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            ExportFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            ExportFormat.WEBP ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, stream)
                } else {
                    @Suppress("DEPRECATION")
                    bitmap.compress(Bitmap.CompressFormat.WEBP, quality, stream)
                }
        }
        return if (ok) stream.toByteArray() else null
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
