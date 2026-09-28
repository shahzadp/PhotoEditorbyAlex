package com.alex.photoeditor.core

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Raised when a picked or shared image cannot be read at all. */
class ImageImportException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Copies an image out of a content provider and into app-private storage.
 *
 * Why this exists: URIs from the photo picker, the document picker and other
 * apps are *temporary grants*. They can be revoked the moment the delivering
 * activity finishes, and a bitmap that is already decoded survives that — but
 * any later re-read (crop, export, project restore) would fail. Copying once,
 * up front, while the grant is definitely valid, means every downstream
 * operation reads from a plain file we own.
 */
object ImageStore {

    private const val DIR_NAME = "images"

    suspend fun import(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val target = File(dir, "${UUID.randomUUID()}.img")
        val resolver = context.contentResolver

        val copied = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { out ->
                    input.copyTo(out, DEFAULT_BUFFER_SIZE)
                }
                true
            } ?: false
        }.getOrElse { t ->
            target.delete()
            throw ImageImportException(
                "Could not read the selected image " +
                    "(${t.javaClass.simpleName}: ${t.message})",
                t
            )
        }

        if (!copied) {
            target.delete()
            throw ImageImportException(
                "The selected file could not be opened. It may have been " +
                    "moved, deleted, or is not a readable image."
            )
        }
        if (target.length() == 0L) {
            target.delete()
            throw ImageImportException("The selected file is empty.")
        }

        target
    }

    /** Best-effort display name taken from the original content URI. */
    fun displayName(uri: Uri): String =
        uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.ifBlank { null }
            ?: "image"

    /**
     * Removes cached copies that are no longer referenced.
     *
     * Only files this class created are touched, so user photos are never at
     * risk. Safe to call with an empty set on app start.
     */
    fun prune(context: Context, keep: Set<File>) {
        val dir = File(context.filesDir, DIR_NAME)
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f !in keep) f.delete()
        }
    }
}
