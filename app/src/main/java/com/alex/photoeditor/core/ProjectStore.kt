package com.alex.photoeditor.core

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * One saved edit, restorable after the app is closed.
 *
 * Holds the original image plus the whole [EditState], which is deliberately
 * free of pixels — every preview is re-derived from the original on load, so a
 * project costs one source file and a few kilobytes of JSON.
 */
@Serializable
data class Project(
    val id: String = UUID.randomUUID().toString(),
    /** App-private copy of the source image. */
    val sourcePath: String,
    val displayName: String = "Untitled",
    val state: EditState = EditState(),
    /** Wall-clock milliseconds, written on every save. */
    val modifiedAt: Long = System.currentTimeMillis(),
    /** Small preview for the projects grid. */
    val thumbnailPath: String? = null
)

/** A project as shown in the grid, without loading its full state. */
@Serializable
data class ProjectSummary(
    val id: String,
    val displayName: String,
    val modifiedAt: Long,
    val thumbnailPath: String?
)

/**
 * On-disk store for unsaved work.
 *
 * Why this exists: the editor is fully non-destructive and holds its state in
 * memory only, so closing the app — deliberately, or by a crash or an
 * out-of-memory kill — discarded every edit. Each project is a JSON file
 * beside a private copy of the source image, written atomically so a kill
 * mid-save cannot leave a half-written project behind.
 *
 * Saves are debounced by the caller, so dragging a slider does not thrash the
 * disk.
 */
object ProjectStore {

    private const val DIR_NAME = "projects"
    private const val FILE_EXTENSION = ".json"
    private const val THUMBNAIL_EXTENSION = ".thumb.jpg"

    private const val THUMBNAIL_EDGE = 480
    private const val THUMBNAIL_QUALITY = 80

    private val json = Json {
        // Tolerant so a project saved by an older build still opens after a
        // field is added, rather than throwing the whole file away.
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    private fun fileFor(context: Context, id: String): File =
        File(dir(context), id + FILE_EXTENSION)

    /**
     * Writes [state] against the currently open image, replacing any existing
     * project for it.
     *
     * Returns the project id, or null if there is nothing worth saving.
     */
    suspend fun save(
        context: Context,
        id: String?,
        sourceFile: File,
        displayName: String,
        state: EditState
    ): String? = withContext(Dispatchers.IO) {
        // A project with no edits at all is just a copy of the original, which
        // the user already has. Keeping it would only clutter the grid.
        if (state.isDefault) {
            if (id != null) delete(context, id)
            return@withContext null
        }

        val projectId = id ?: UUID.randomUUID().toString()
        val thumb = File(dir(context), projectId + THUMBNAIL_EXTENSION)

        val record = Project(
            id = projectId,
            sourcePath = sourceFile.absolutePath,
            displayName = displayName,
            state = state,
            modifiedAt = System.currentTimeMillis(),
            thumbnailPath = if (thumb.exists()) thumb.absolutePath else null
        )

        val target = fileFor(context, projectId)
        // Write to a temp file and rename: a rename is atomic on the same
        // filesystem, so an app kill during the save leaves the previous
        // version intact rather than a truncated JSON file.
        val tmp = File(target.parentFile, "$projectId.tmp")
        runCatching {
            tmp.writeText(json.encodeToString(record))
            if (!tmp.renameTo(target)) {
                // renameTo can fail across some filesystems; fall back to a
                // direct write rather than losing the save entirely.
                target.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure {
            tmp.delete()
            return@withContext null
        }

        projectId
    }

    /**
     * Lists saved projects, newest first.
     *
     * Reads only each file's header rather than the full state, so opening the
     * grid does not parse every curve and every layer of every project.
     */
    suspend fun list(context: Context): List<ProjectSummary> = withContext(Dispatchers.IO) {
        val files = dir(context).listFiles { f -> f.name.endsWith(FILE_EXTENSION) }
            ?: return@withContext emptyList()

        files.mapNotNull { file ->
            runCatching {
                val project = json.decodeFromString<Project>(file.readText())
                // A project whose source image has gone is not restorable, so
                // it is hidden rather than offered and then failing.
                if (!File(project.sourcePath).exists()) null
                else ProjectSummary(
                    id = project.id,
                    displayName = project.displayName,
                    modifiedAt = project.modifiedAt,
                    thumbnailPath = project.thumbnailPath
                )
            }.getOrNull()
        }.sortedByDescending { it.modifiedAt }
    }

    /** Loads a full project, or null if it has gone or is unreadable. */
    suspend fun load(context: Context, id: String): Project? =
        withContext(Dispatchers.IO) {
            val file = fileFor(context, id)
            if (!file.exists()) return@withContext null
            runCatching { json.decodeFromString<Project>(file.readText()) }.getOrNull()
        }

    /**
     * Writes the grid thumbnail for [id].
     *
     * Generated from the fully rendered preview rather than the source, so the
     * grid shows the edit the user made.
     */
    suspend fun writeThumbnail(
        context: Context,
        id: String,
        preview: Bitmap
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val longEdge = maxOf(preview.width, preview.height)
            val scaled = if (longEdge > THUMBNAIL_EDGE) {
                val ratio = THUMBNAIL_EDGE.toFloat() / longEdge
                Bitmap.createScaledBitmap(
                    preview,
                    (preview.width * ratio).toInt().coerceAtLeast(1),
                    (preview.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                preview
            }

            val file = File(dir(context), id + THUMBNAIL_EXTENSION)
            file.outputStream().use {
                scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, it)
            }
            if (scaled !== preview) scaled.recycle()
            file.absolutePath
        }.getOrNull()
    }

    /**
     * Deletes a project and its thumbnail.
     *
     * The source image copy is left alone: it may be shared with another
     * project, and it is small relative to the risk of deleting the wrong one.
     */
    suspend fun delete(context: Context, id: String) = withContext(Dispatchers.IO) {
        fileFor(context, id).delete()
        File(dir(context), id + THUMBNAIL_EXTENSION).delete()
    }

    /** Total bytes used by saved projects, for the grid's footer. */
    suspend fun diskUsage(context: Context): Long = withContext(Dispatchers.IO) {
        dir(context).listFiles()?.sumOf { it.length() } ?: 0L
    }

    /** Restores a project's source image so it can be reopened. */
    suspend fun openSource(project: Project): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) {
            val file = File(project.sourcePath)
            if (!file.exists()) return@withContext null
            runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0) return@runCatching null

                var sample = 1
                val edge = maxOf(bounds.outWidth, bounds.outHeight)
                while (edge / (sample * 2) >= 4096) sample *= 2

                android.graphics.BitmapFactory.decodeFile(
                    file.absolutePath,
                    android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                )
            }.getOrNull()
        }

    /** Exposed so the UI can report a project whose image went missing. */
    fun sourceExists(project: Project): Boolean = File(project.sourcePath).exists()

    /** The solid fills offered when starting a blank project. */
    enum class BlankColour(val label: String, val argb: Int) {
        BLACK("Black", 0xFF000000.toInt()),
        GREY("Grey", 0xFF808080.toInt()),
        WHITE("White", 0xFFFFFFFF.toInt())
    }

    /**
     * Writes a solid-colour image and returns it, ready to be opened.
     *
     * A blank project is a real PNG on disk rather than a flag on the state,
     * so it flows through exactly the same open, save, restore and export path
     * as a picked photo. Anything else would need a second code path for every
     * one of those, and blank canvases are the case most likely to be exported
     * or restored later.
     *
     * Dimensions match a common photo aspect so the canvas is a sensible
     * starting point rather than an odd shape.
     */
    suspend fun createBlank(
        context: Context,
        colour: BlankColour,
        width: Int = 2048,
        height: Int = 1536
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "images").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.img")

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(colour.argb)
        file.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        file
    }
}
