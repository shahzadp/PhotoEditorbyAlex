package com.alex.photoeditor.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alex.photoeditor.core.Adjustments
import com.alex.photoeditor.core.AdjustmentKind
import com.alex.photoeditor.core.AspectPreset
import com.alex.photoeditor.core.CropRect
import com.alex.photoeditor.core.EditState
import com.alex.photoeditor.core.EditorRepository
import com.alex.photoeditor.core.ExportFormat
import com.alex.photoeditor.core.ExportSettings
import com.alex.photoeditor.core.Exporter
import com.alex.photoeditor.core.FlipAxis
import com.alex.photoeditor.core.PreviewState
import com.alex.photoeditor.ui.components.CropSelection
import com.alex.photoeditor.ui.components.toCropRect
import com.alex.photoeditor.ui.components.toSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Which tool panel is showing. */
enum class EditorTool(val label: String) {
    ADJUST("Adjust"),
    FILTERS("Filters"),
    CROP("Crop"),
    TRANSFORM("Transform"),
    INFO("Info")
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    val repository = EditorRepository()
    private val exporter = Exporter(app)

    val preview: kotlinx.coroutines.flow.StateFlow<PreviewState> = repository.preview

    var activeTool by mutableStateOf(EditorTool.ADJUST)
        private set

    var activeAdjustment by mutableStateOf(AdjustmentKind.EXPOSURE)
        private set

    var activeFilterId by mutableStateOf("original")
        private set

    var showExportSheet by mutableStateOf(false)
        private set

    var exportFormat by mutableStateOf(ExportFormat.JPEG)
        private set

    var exportQuality by mutableStateOf(92)
        private set

    var statusMessage by mutableStateOf<String?>(null)
        private set

    /** True while the user holds the compare button. */
    var isComparing by mutableStateOf(false)
        private set

    var isExporting by mutableStateOf(false)
        private set

    private val _shareRequest = MutableStateFlow<Uri?>(null)
    val shareRequest: kotlinx.coroutines.flow.StateFlow<Uri?> = _shareRequest

    private var exportBitmap: Bitmap? = null

    /**
     * Dimensions of the uncropped crop canvas (the frame the overlay sits on).
     * Used only to convert fractions to full-resolution pixels on commit.
     */
    private fun cropCanvasSize(): Pair<Int, Int>? {
        val bmp = preview.value.cropBase ?: return null
        return bmp.width to bmp.height
    }

    // ------------------------------------------------------------- actions

    fun openImage(uri: Uri) {
        viewModelScope.launch { repository.open(getApplication(), uri) }
    }

    fun selectTool(tool: EditorTool) {
        activeTool = tool
        // Tells the renderer to produce the uncropped crop canvas.
        repository.activeToolIsCrop = (tool == EditorTool.CROP)
        if (tool == EditorTool.CROP) {
            // Re-render so cropBase becomes available.
            repository.refresh()
        }
        if (tool != EditorTool.CROP) {
            // Leaving crop mode ends the crop session.
            cropSession = null
        } else {
            // Entering crop mode seeds the session from the stored crop.
            cropSession = currentCropSelection()
        }
    }

    fun selectAdjustment(kind: AdjustmentKind) {
        activeAdjustment = kind
    }

    fun selectFilter(id: String) {
        activeFilterId = id
        repository.setFilter(id)
    }

    fun onSliderChange(kind: AdjustmentKind, value: Float) {
        repository.setAdjustment(kind, value)
    }

    fun onSliderRelease() {
        repository.commitAdjustmentGesture()
    }

    fun resetAdjustments() = repository.resetAdjustments()

    fun resetAll() = repository.resetAll()

    /** Press-and-hold compare against the untouched original. */
    fun toggleComparing(held: Boolean) {
        isComparing = held
    }

    fun rotateLeft() = repository.rotate(-1)
    fun rotateRight() = repository.rotate(1)
    fun flipHorizontal() = repository.flip(FlipAxis.HORIZONTAL)
    fun flipVertical() = repository.flip(FlipAxis.VERTICAL)

    fun setStraighten(deg: Float) = repository.setStraighten(deg)

    fun setAspect(preset: AspectPreset) {
        repository.setAspect(preset)
        // A preset replaces the crop outright, so the session must follow.
        cropSession = currentCropSelection()
    }

    // ---------------------------------------------------------- crop drags

    fun beginCrop() = repository.beginCropGesture()

    /**
     * Authoritative crop selection while the crop tool is open.
     *
     * The overlay used to round-trip every drag frame through the repository as
     * full-resolution pixels, then back to fractions. On a 4096px photo a
     * 6%-tall crop is only ~245px, so each round trip quantised the selection
     * and the frame visibly shrank as you tried to make it smaller.
     *
     * Holding the selection here as fractions — and only converting to pixels
     * when the user lets go — keeps the drag exact.
     */
    private var cropSession: CropSelection? = null

    /** The selection the crop overlay should display. */
    fun activeCropSelection(): CropSelection =
        cropSession ?: currentCropSelection()

    fun updateCrop(sel: CropSelection) {
        cropSession = sel
        val (w, h) = cropFullSize() ?: return
        repository.updateCropLive(sel.toCropRect(w, h))
    }

    fun commitCrop(sel: CropSelection) {
        cropSession = sel
        val (w, h) = cropFullSize() ?: return
        repository.updateCropLive(sel.toCropRect(w, h))
        repository.endCropGesture()
    }

    /**
     * The crop canvas in FULL-RESOLUTION pixels.
     *
     * The overlay works in fractions of the *downscaled* crop canvas, but the
     * stored rect is full-resolution — that is what the renderer and
     * `setAspect` assume, and what export needs.
     *
     * Converting fractions against the downscaled canvas instead stored a
     * 1440px-space rect into a field read as 4096px-space. The renderer then
     * rescaled it by 1440/4096, so the applied crop was a small region near the
     * origin, nowhere near the box the user had drawn.
     */
    private fun cropFullSize(): Pair<Int, Int>? {
        val (cw, ch) = cropCanvasSize() ?: return null
        if (cw <= 0 || ch <= 0) return null

        val fullW = repository.geometryWidthForCrop()
        if (fullW <= 0) return null

        val fullH = (fullW.toFloat() * ch.toFloat() / cw.toFloat()).roundToInt()
        if (fullH <= 0) return null
        return fullW to fullH
    }

    /**
     * Current crop as a fractional selection.
     *
     * The stored rect is in FULL-RESOLUTION pixels, while the overlay works in
     * fractions of the downscaled crop canvas. Dividing one by the other
     * directly produced values greater than 1, which fed an out-of-range
     * selection back into the drag maths and crashed the app.
     */
    fun currentCropSelection(): CropSelection {
        val (fullW, fullH) = cropFullSize() ?: return CropSelection.FULL
        val crop = repository.currentState().crop ?: return CropSelection.FULL
        return crop.toSelection(fullW, fullH)
    }

    fun resetCrop() {
        repository.update({ it.copy(crop = null) }, "Reset crop")
        cropSession = CropSelection.FULL
    }

    /**
     * Which aspect chip should look selected.
     *
     * Free when there is no crop, otherwise the preset whose ratio matches the
     * current crop within tolerance. A hand-dragged crop reports Free, since it
     * no longer corresponds to any preset.
     */
    fun activeAspectPreset(): AspectPreset {
        val (w, h) = cropCanvasSize() ?: return AspectPreset.FREE
        if (w <= 0 || h <= 0) return AspectPreset.FREE

        val crop = repository.currentState().crop ?: return AspectPreset.FREE
        // Compare the cropped pixel span against the canvas, not the source,
        // because the stored rect is in full-resolution pixels.
        val baseW = repository.geometryWidthForCrop()
        if (baseW <= 0) return AspectPreset.FREE

        val scale = baseW.toFloat() / w.toFloat()
        val croppedW = (crop.right - crop.left) * scale
        val croppedH = (crop.bottom - crop.top) * scale
        if (croppedW <= 0f || croppedH <= 0f) return AspectPreset.FREE

        val ratio = croppedW / croppedH
        return AspectPreset.entries.firstOrNull { p ->
            p.ratio != null && kotlin.math.abs(p.ratio - ratio) < 0.02f
        } ?: AspectPreset.FREE
    }

    fun undo() = repository.undo()
    fun redo() = repository.redo()

    fun currentAdjustments(): Adjustments = repository.currentState().adjustments

    fun currentState(): EditState = repository.currentState()

    // -------------------------------------------------------------- export

    fun openExportSheet() {
        showExportSheet = true
    }

    fun closeExportSheet() {
        showExportSheet = false
    }

    fun chooseExportFormat(format: ExportFormat) {
        exportFormat = format
    }

    fun chooseExportQuality(q: Int) {
        exportQuality = q.coerceIn(1, 100)
    }

    fun saveToGallery() {
        if (isExporting) return
        viewModelScope.launch {
            isExporting = true
            val bitmap = repository.renderForExport()
            if (bitmap == null) {
                statusMessage = "Nothing to export"
                isExporting = false
                return@launch
            }
            exportBitmap = bitmap
            val settings = ExportSettings(
                format = exportFormat,
                quality = exportQuality,
                fileName = preview.value.info?.displayName?.substringBeforeLast('.')
            )
            val uri = exporter.saveToGallery(bitmap, settings)
            isExporting = false
            showExportSheet = false
            statusMessage = if (uri != null) {
                "Saved to gallery"
            } else {
                "Could not write the file"
            }
        }
    }

    fun share() {
        if (isExporting) return
        viewModelScope.launch {
            isExporting = true
            val bitmap = repository.renderForExport()
            if (bitmap == null) {
                statusMessage = "Nothing to share"
                isExporting = false
                return@launch
            }
            exportBitmap = bitmap
            val settings = ExportSettings(
                format = exportFormat,
                quality = exportQuality,
                fileName = preview.value.info?.displayName?.substringBeforeLast('.')
            )
            val uri = exporter.createShareUri(bitmap, settings)
            isExporting = false
            showExportSheet = false
            if (uri != null) _shareRequest.value = uri
        }
    }

    fun consumeShareRequest() {
        _shareRequest.value = null
    }

    fun clearStatus() {
        statusMessage = null
    }

    override fun onCleared() {
        super.onCleared()
        repository.release()
        exportBitmap?.recycle()
        exportBitmap = null
    }
}
