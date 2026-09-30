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
import com.alex.photoeditor.core.CutoutComposer
import com.alex.photoeditor.core.CutoutState
import com.alex.photoeditor.core.DrawBrushes
import com.alex.photoeditor.core.DrawFill
import com.alex.photoeditor.core.DrawStroke
import com.alex.photoeditor.core.EditState
import com.alex.photoeditor.core.EditorRepository
import com.alex.photoeditor.core.ExportFormat
import com.alex.photoeditor.core.ExportSettings
import com.alex.photoeditor.core.Exporter
import com.alex.photoeditor.core.FlipAxis
import com.alex.photoeditor.core.ImageLayer
import com.alex.photoeditor.core.PreviewState
import com.alex.photoeditor.core.ProjectStore
import com.alex.photoeditor.core.ProjectSummary
import com.alex.photoeditor.core.StrokePoint
import com.alex.photoeditor.core.TapSelectEngine
import com.alex.photoeditor.ui.components.CropSelection
import com.alex.photoeditor.ui.components.toCropRect
import com.alex.photoeditor.ui.components.toSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Which tool panel is showing. */
enum class EditorTool(val label: String) {
    ADJUST("Adjust"),
    FILTERS("Filters"),
    CROP("Crop"),
    TRANSFORM("Transform"),
    TAP_SELECT("Cutout"),
    LAYERS("Add image"),
    TEXT("Text"),
    DRAW("Draw"),
    INFO("Info")
}

/**
 * How long editing must settle before the session is written to disk.
 *
 * Long enough that a continuous slider drag produces one save rather than one
 * per frame, short enough that a crash loses well under a second of work.
 */
private const val AUTOSAVE_DELAY_MS = 800L

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * The repository is created here rather than by default construction
     * because the MediaPipe segmenter needs the application Context.
     */
    val repository = EditorRepository(
        cutoutComposer = CutoutComposer(TapSelectEngine(app))
    )
    private val exporter = Exporter(app)

    init {
        // Autosave reacts to a single revision counter rather than to each
        // setter, so no state-mutating path can be left out of the backup.
        viewModelScope.launch {
            repository.revision.drop(1).collect { scheduleAutosave() }
        }
    }

    val preview: kotlinx.coroutines.flow.StateFlow<PreviewState> = repository.preview

    var activeTool by mutableStateOf(EditorTool.ADJUST)
        private set

    /**
     * True when the tool panel and rail are hidden for a larger preview.
     *
     * Deliberately remembered in the ViewModel rather than in Compose state so
     * it survives switching tools: collapsing is a way of looking at the photo,
     * not a property of whichever panel happens to be open.
     */
    var panelCollapsed by mutableStateOf(false)
        private set

    fun togglePanelCollapsed() {
        panelCollapsed = !panelCollapsed
    }

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

    /** True while the projects sheet is showing. */
    var showProjects by mutableStateOf(false)
        private set

    /** Saved projects, newest first. */
    var projects by mutableStateOf<List<ProjectSummary>>(emptyList())
        private set

    var isLoadingProjects by mutableStateOf(false)
        private set

    /** True while the "new project" chooser is showing. */
    var showNewProject by mutableStateOf(false)
        private set

    fun openNewProjectChooser() {
        showNewProject = true
    }

    fun closeNewProject() {
        showNewProject = false
    }

    /**
     * Starts a blank project on a solid canvas.
     *
     * The colour becomes a real image file on disk rather than a flag on the
     * state, so the project saves, restores and exports through exactly the
     * same path as a picked photo.
     */
    fun startBlankProject(colour: ProjectStore.BlankColour) {
        showNewProject = false
        showProjects = false
        viewModelScope.launch {
            val file = ProjectStore.createBlank(getApplication(), colour)
            val name = "${colour.label} canvas"
            // Deliberately not written to the projects grid yet: a blank canvas
            // has no edits, and autosave treats an unedited state as nothing
            // worth keeping. The project appears once the user actually
            // changes something, which is the behaviour they already expect
            // from undoing back to the original.
            repository.adoptBlankSource(getApplication(), file, name)
        }
    }

    /**
     * Debounced autosave.
     *
     * Every state change restarts a short timer rather than writing
     * immediately, so dragging an adjustment slider does not hammer the disk
     * with a JSON file per frame. A crash loses at most the last
     * [AUTOSAVE_DELAY_MS] of work instead of the whole session.
     */
    private var autosaveJob: Job? = null

    private fun scheduleAutosave() {
        if (repository.currentState().isDefault) return
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            repository.autosave(getApplication())
        }
    }

    /**
     * Writes any pending work immediately.
     *
     * Called when the editor is left, so closing the app deliberately does not
     * race the debounce timer.
     */
    fun flushAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            repository.autosave(getApplication())
        }
    }

    private suspend fun refreshProjects() {
        isLoadingProjects = true
        projects = ProjectStore.list(getApplication())
        isLoadingProjects = false
    }

    fun openProjects() {
        showProjects = true
        viewModelScope.launch { refreshProjects() }
    }

    fun closeProjects() {
        showProjects = false
    }

    /** Opens a saved project, replacing whatever is currently open. */
    fun openProject(id: String) {
        viewModelScope.launch {
            val project = ProjectStore.load(getApplication(), id) ?: run {
                statusMessage = "That project could not be opened"
                refreshProjects()
                return@launch
            }
            if (!ProjectStore.sourceExists(project)) {
                statusMessage = "The original image for that project is missing"
                return@launch
            }
            showProjects = false
            repository.openProject(getApplication(), project)
        }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            repository.deleteProject(getApplication(), id)
            refreshProjects()
        }
    }

    private val _shareRequest = MutableStateFlow<Uri?>(null)
    val shareRequest: kotlinx.coroutines.flow.StateFlow<Uri?> = _shareRequest

    // ---------------------------------------------------------- text layers

    /** Which text object the panel is editing, or null. */
    var activeTextId by mutableStateOf<String?>(null)
        private set

    /**
     * True while the stage is sampling a colour from the photo.
     *
     * Held here rather than in the panel because it changes what the *preview*
     * does, and the panel does not own the preview. Cleared whenever the
     * selected text changes, so switching objects never leaves the stage
     * armed for sampling when the user expects to be able to drag.
     */
    var eyedropperActive by mutableStateOf(false)
        private set

    fun setEyedropper(on: Boolean) {
        eyedropperActive = on
    }

    fun addText(): String? {
        val id = repository.addText()
        if (id != null) {
            activeTextId = id
            eyedropperActive = false
        }
        return id
    }

    fun selectText(id: String) {
        activeTextId = id
        eyedropperActive = false
    }

    fun setTextContent(id: String, text: String) = repository.setTextContent(id, text)
    fun setTextSize(id: String, f: Float) = repository.setTextSize(id, f)
    fun setTextOffset(id: String, x: Float, y: Float) = repository.setTextOffset(id, x, y)
    fun setTextRotation(id: String, d: Float) = repository.setTextRotation(id, d)
    fun setTextColor(id: String, argb: Long) = repository.setTextColor(id, argb)
    fun setTextFont(id: String, key: String) = repository.setTextFont(id, key)
    fun setTextBold(id: String, bold: Boolean) = repository.setTextStyle(id, bold = bold)
    fun setTextItalic(id: String, italic: Boolean) = repository.setTextStyle(id, italic = italic)
    fun setTextOpacity(id: String, o: Float) = repository.setTextOpacity(id, o)
    fun setTextVisible(id: String, visible: Boolean) = repository.setTextVisible(id, visible)
    fun resetText(id: String) = repository.resetText(id)

    /**
     * Live transform during a drag or pinch.
     *
     * Goes through the repository's no-history path, so a whole gesture costs
     * one undo step rather than one per pointer event.
     */
    fun setLayerTransformLive(id: String, x: Float, y: Float, scale: Float, deg: Float) =
        repository.setLayerTransformLive(id, x, y, scale, deg)

    fun setTextTransformLive(id: String, x: Float, y: Float, fraction: Float, deg: Float) =
        repository.setTextTransformLive(id, x, y, fraction, deg)

    fun endTransformGesture(label: String) = repository.endTransformGesture(label)

    fun removeText(id: String) {
        repository.removeText(id)
        if (activeTextId == id) activeTextId = null
    }

    // ------------------------------------------------------------------ draw

    /** The colour the brush and the fill both use. */
    var drawColor by mutableStateOf(0xFF000000L)
        private set

    var drawBrushKey by mutableStateOf(DrawBrushes.DEFAULT_KEY)
        private set

    /**
     * Brush width as a fraction of the image's shorter edge.
     *
     * Stored as a fraction rather than as dp so the stroke keeps its visual
     * weight on any photo size and at export resolution.
     */
    var drawWidthFraction by mutableStateOf(0.012f)
        private set

    /** True to erase instead of painting. */
    var drawErase by mutableStateOf(false)
        private set

    var drawOpacity by mutableStateOf(1f)
        private set

    /**
     * True while the fill tool is armed.
     *
     * A mode rather than a separate button because both write with the same
     * colour, and switching between them is exactly one tap.
     */
    var drawFillMode by mutableStateOf(false)
        private set

    /**
     * Separate from the text eyedropper.
     *
     * Both sample the same flattened preview, but they write to different
     * targets - a text colour and the brush colour - so sharing the flag
     * would mean arming one silently armed the other.
     */
    var drawEyedropperActive by mutableStateOf(false)
        private set

    /** Id of the stroke currently being drawn, while a gesture is in flight. */
    var liveStrokeId: String? = null
        private set

    val drawStrokeCount: Int get() = repository.strokeCount
    val hasDrawing: Boolean get() = repository.hasDrawing

    fun setBrushColor(argb: Long) {
        drawColor = argb
        // Repainting existing marks when the colour changes is what people
        // expect from a drawing app: pick a new colour, everything you drew
        // takes it.
        if (repository.hasDrawing) repository.recolourDrawing(argb)
    }

    fun setDrawBrush(key: String) {
        drawBrushKey = key
    }

    fun setDrawWidth(fraction: Float) {
        drawWidthFraction = fraction.coerceIn(0.0015f, 0.5f)
    }

    fun setErasing(on: Boolean) {
        drawErase = on
        if (on) drawFillMode = false
    }

    fun setFillArmed(on: Boolean) {
        drawFillMode = on
        if (on) {
            drawErase = false
            drawEyedropperActive = false
        }
    }

    fun setBrushOpacity(value: Float) {
        drawOpacity = value.coerceIn(0f, 1f)
    }

    fun setDrawEyedropper(on: Boolean) {
        drawEyedropperActive = on
        if (on) drawFillMode = false
    }

    fun clearDrawing() = repository.clearDrawing()

    /** Called once when a drawing gesture starts. */
    fun beginStroke(): String {
        val id = java.util.UUID.randomUUID().toString()
        liveStrokeId = id
        repository.addStroke(
            DrawStroke(
                id = id,
                color = drawColor,
                widthFraction = drawWidthFraction,
                brushKey = drawBrushKey,
                erase = drawErase,
                opacity = drawOpacity
            )
        )
        return id
    }

    fun extendStroke(id: String, points: List<StrokePoint>) =
        repository.appendStrokePoints(id, points)

    fun endStroke() {
        liveStrokeId = null
    }

    /** Fills the region around a normalised point in image space. */
    fun fillAt(x: Float, y: Float) {
        repository.addFill(
            DrawFill(id = java.util.UUID.randomUUID().toString(), x = x, y = y, color = drawColor)
        )
    }

    /** Colour sampled from the preview by the draw eyedropper. */
    fun applyDrawSample(argb: Long) = setBrushColor(argb)

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

    /** True when an image is open, so Back should return to the picker. */
    val hasImage: Boolean get() = preview.value.bitmap != null

    /** Closes the open image and returns to the empty picker screen. */
    fun closeImage() {
        cropSession = null
        showExportSheet = false
        isComparing = false
        // Write any debounced work out before the state is torn down, or the
        // last few seconds of edits are lost.
        flushAutosave()
        repository.close()
    }

    fun selectTool(tool: EditorTool) {
        activeTool = tool
        // A collapsed panel is a deliberate choice about viewing the photo, so
        // it survives switching tools rather than springing open every time.
        // Tells the renderer to produce the uncropped geometry render. Both
        // crop and tap-to-select need it: the crop overlay sits on it, and
        // taps are recorded against the full uncropped frame so a later crop
        // does not invalidate the selection.
        val needsUncropped = tool == EditorTool.CROP || tool == EditorTool.TAP_SELECT
        repository.activeToolIsCrop = needsUncropped
        if (needsUncropped) {
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

    // ---------------------------------------------------------- cutout taps

    /** Current background-removal selection. */
    fun currentCutout(): CutoutState = repository.currentState().cutout

    fun addTap(x: Float, y: Float) = repository.addTap(x, y)

    /** Starts a fresh selection from a single tap. */
    fun replaceTap(x: Float, y: Float) = repository.addTap(x, y, replace = true)

    /**
     * Handles a tap on the photo.
     *
     * A long press restarts the selection, a plain tap extends it, but the
     * first tap of a fresh selection always replaces so the user does not have
     * to clear before starting again.
     */
    fun selectAt(x: Float, y: Float, replace: Boolean) {
        val existing = repository.currentState().cutout
        if (replace || !existing.isActive) {
            replaceTap(x, y)
        } else {
            addTap(x, y)
        }
    }

    fun clearCutout() = repository.clearCutout()

    fun setKeepSubject(keep: Boolean) = repository.setCutoutKeepSubject(keep)

    fun setCutoutThreshold(v: Float) = repository.setCutoutThreshold(v)

    fun setCutoutFeather(v: Float) = repository.setCutoutFeather(v)

    // --------------------------------------------------------- image layers

    /** Which layer the panel is editing, or null when the stack is empty. */
    var activeLayerId by mutableStateOf<String?>(null)
        private set

    /**
     * The layer currently selected for editing, or null.
     *
     * Callers pass [ImageLayer] down as a composable parameter rather than
     * calling an accessor here. Reading `preview.value` inside a composable
     * does not register a snapshot read, so the UI silently stopped
     * recomposing; taking the stack from the collected state in
     * `EditorScreen` and passing it in is what makes the controls live.
     */
    fun setActiveLayer(id: String?) {
        activeLayerId = id
    }

    fun addLayer(uri: Uri) {
        viewModelScope.launch {
            repository.addLayer(getApplication(), uri)
            // Select whatever was just added so its transform is editable.
            activeLayerId = repository.currentLayers().lastOrNull()?.id
        }
    }

    fun selectLayer(id: String) {
        activeLayerId = id
    }

    fun setLayerScale(id: String, v: Float) = repository.setLayerScale(id, v)
    fun setLayerOffset(id: String, x: Float, y: Float) = repository.setLayerOffset(id, x, y)
    fun setLayerRotation(id: String, d: Float) = repository.setLayerRotation(id, d)
    fun setLayerOpacity(id: String, v: Float) = repository.setLayerOpacity(id, v)
    fun setLayerVisible(id: String, v: Boolean) = repository.setLayerVisible(id, v)
    fun resetLayer(id: String) = repository.resetLayer(id)
    fun removeLayer(id: String) {
        repository.removeLayer(id)
        if (activeLayerId == id) {
            activeLayerId = repository.currentLayers().lastOrNull()?.id
        }
    }

    fun bringLayerToFront(id: String) = repository.bringLayerToFront(id)
    fun sendLayerToBack(id: String) = repository.sendLayerToBack(id)

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
