package com.alex.photoeditor.core

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * The complete, serialisable description of an edit.
 *
 * Nothing here holds pixels â€” only the original bitmap is retained, and every
 * render is derived from it. That is what makes the editor non-destructive:
 * changing any value simply re-derives the preview from [original].
 */
@Serializable
data class EditState(
    val adjustments: Adjustments = Adjustments.NEUTRAL,
    val filterId: String = "original",
    val filterStrength: Float = 1f,
    val rotationSteps: Int = 0,
    val straightenDegrees: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val crop: CropRect? = null,
    val resizeWidth: Int? = null,
    val resizeHeight: Int? = null,
    /** Phase 2 professional colour: curves, levels, HSL, selective colour. */
    val colorGrade: ColorGrade = ColorGrade(),
    /**
     * Background removal from the "tap to select" tool.
     *
     * The mask is held as normalised anchor points rather than pixels so the
     * state stays small, serialisable and non-destructive. The mask is
     * re-derived from the original on every render, so changing any other
     * setting later reuses the same selection.
     */
    val cutout: CutoutState = CutoutState(),
    /**
     * Images stacked on top of the base photo, in draw order.
     *
     * At most [ImageLayer.MAX_LAYERS]. Held as file paths plus transforms so
     * the state remains serialisable; the bitmaps live in [LayerStore].
     */
    val layers: List<ImageLayer> = emptyList(),
    /**
     * Text stacked over the base photo.
     *
     * A separate list from [layers] with its own cap, so ten images plus ten
     * text objects can coexist. Text holds no bitmap — it is drawn as vectors
     * on the Canvas — so a full set of text layers costs no extra memory.
     */
    val textLayers: List<TextLayer> = emptyList(),
    /**
     * Freehand strokes drawn over the photo.
     *
     * Separate from [layers] and [textLayers] because a stroke is a polyline
     * rather than a bitmap or a string, and it needs its own lifecycle: it
     * accumulates unboundedly as the user draws, so it has no per-layer cap
     * and is cleared wholesale by the eraser.
     */
    val strokes: List<DrawStroke> = emptyList(),
    /**
     * Flood-filled regions, each a seed plus a tolerance.
     *
     * Re-derived from the image on every render rather than stored as a mask,
     * so a fill survives a crop, rotate or resize and costs a handful of bytes.
     */
    val fills: List<DrawFill> = emptyList()
) {
    val isDefault: Boolean
        get() = adjustments.isNeutral &&
            filterId == "original" &&
            rotationSteps == 0 &&
            straightenDegrees == 0f &&
            !flipH && !flipV &&
            crop == null &&
            resizeWidth == null &&
            resizeHeight == null &&
            colorGrade.isNeutral &&
            cutout.isNone &&
            layers.isEmpty() &&
            textLayers.isEmpty() &&
            strokes.isEmpty() &&
            fills.isEmpty()

    /** Human-readable summary for the history panel. */
    fun describe(): String = buildList {
        if (rotationSteps != 0) add("Rotate ${(rotationSteps * 90) % 360}Â°")
        if (straightenDegrees != 0f) add("Straighten ${"%.1f".format(straightenDegrees)}Â°")
        if (flipH) add("Flip horizontal")
        if (flipV) add("Flip vertical")
        if (crop != null) add("Crop")
        if (resizeWidth != null) add("Resize")
        if (cutout.isActive) add("Cutout")
        if (layers.isNotEmpty()) {
            add(if (layers.size == 1) "1 layer" else "${layers.size} layers")
        }
        if (textLayers.isNotEmpty()) {
            val n = textLayers.size
            add(if (n == 1) "1 text" else "$n texts")
        }
        if (strokes.isNotEmpty()) {
            add(if (strokes.size == 1) "1 stroke" else "${strokes.size} strokes")
        }
        if (fills.isNotEmpty()) {
            add(if (fills.size == 1) "1 fill" else "${fills.size} fills")
        }
        if (filterId != "original") add("Filter")
        if (!colorGrade.isNeutral) {
            val touched = buildList {
                if (colorGrade.curves.values.any { !it.isIdentity }) add("Curves")
                if (colorGrade.levels.values.any { !it.isIdentity }) add("Levels")
                if (!colorGrade.hsl.isIdentity) add("HSL")
                if (colorGrade.selectiveColor.isEnabled) add("Selective colour")
            }
            add(touched.joinToString(" + "))
        }
        val touched = AdjustmentKind.entries.count { it.valueIn(adjustments) != 0f }
        if (touched > 0) add("$touched adjustment${if (touched > 1) "s" else ""}")
    }.joinToString(", ").ifEmpty { "Original" }
}

/** One entry in the undo stack. */
data class HistoryEntry(
    val state: EditState,
    val label: String
)

/**
 * A "tap to select" background removal.
 *
 * [anchors] are normalised points (0..1) in the *uncropped* image space where
 * the user tapped. Each tap selects the object under that point, and all taps
 * together build up the mask. Storing points rather than the mask itself keeps
 * the state tiny and lets the model re-run at whatever resolution the current
 * render needs.
 */
@Serializable
data class CutoutState(
    val anchors: List<TapAnchor> = emptyList(),
    /** True to keep the selected object, false to remove it. */
    val keepSubject: Boolean = true,
    /** Confidence threshold in [0,1]; below this counts as background. */
    val threshold: Float = 0.5f,
    /** Feathering width, so the edge is not a hard jaggy line. */
    val feather: Float = 0.12f
) {
    val isActive: Boolean get() = anchors.isNotEmpty()
    val isNone: Boolean get() = !isActive

    /** True when taps should add to the selection rather than replace it. */
    val additive: Boolean get() = anchors.size > 1
}

/** A single tap, in normalised image coordinates. */
@Serializable
data class TapAnchor(val x: Float, val y: Float)

/** Everything the UI needs to render one frame. */
data class PreviewState(
    val bitmap: Bitmap? = null,
    val original: Bitmap? = null,
    /** Uncropped geometry-only render, used as the crop overlay canvas. */
    val cropBase: Bitmap? = null,
    val info: ImageInfo? = null,
    val sourceUri: Uri? = null,
    val isLoading: Boolean = false,
    val isRendering: Boolean = false,
    val error: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val historyIndex: Int = 0,
    val historySize: Int = 0,
    val compareHeld: Boolean = false,
    /**
     * True until a grid thumbnail has been written for this project.
     *
     * Tracked in the state so the autosave path can throttle thumbnail writes
     * without a separate flag on the repository.
     */
    val thumbnailMissing: Boolean = true,
    /**
     * The text stack, mirrored here so the UI can observe it.
     *
     * Mirrored for the same reason as [layers]: a composable must take this
     * from the collected state, not read the repository directly, or it will
     * not recompose when the stack changes.
     */
    val textLayers: List<TextLayer> = emptyList(),
    /**
     * The layer stack, mirrored here so the UI can observe it.
     *
     * The panel must read the stack from an observable source: reading it
     * straight from the repository left the composable holding a stale
     * `visible` flag, so the eye toggle kept setting it to the same value and
     * a hidden layer could never be shown again.
     */
    val layers: List<ImageLayer> = emptyList(),
    /**
     * The drawn strokes, mirrored here so the panel can observe the stack.
     *
     * Read from the collected state rather than from the repository directly:
     * a direct read registers no snapshot observation, so the panel would not
     * recompose when a stroke was added.
     */
    val strokes: List<DrawStroke> = emptyList()
)

/**
 * Owns edit state, history and rendering.
 *
 * Design notes:
 *  - **Non-destructive**: only [original] is stored; all previews are derived.
 *  - **Two-tier rendering**: a small preview bitmap for interactive feedback
 *    and the full-size bitmap only for export.
 *  - **Slider coalescing**: rapid slider changes update state immediately but
 *    schedule a debounced re-render, so dragging stays smooth.
 *  - **Serialised renders**: a mutex prevents overlapping pipelines from
 *    interleaving when the user flicks between tools.
 */
class EditorRepository(
    private val adjustmentEngine: AdjustmentEngine = AdjustmentEngine(),
    private val filterEngine: FilterEngine = FilterEngine(),
    private val geometryEngine: GeometryEngine = GeometryEngine(),
    private val colorGradeEngine: ColorGradeEngine = ColorGradeEngine(),
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Background removal. Supplied by the ViewModel because the MediaPipe
     * engine needs a Context, and the repository itself has none.
     */
    private val cutoutComposer: CutoutComposer? = null
) {

    /**
     * Runs the pixel stack. Created lazily so it reuses the same engines this
     * repository already holds, rather than instantiating duplicate ones.
     */
    private val renderer = PipelineRenderer(
        adjustmentEngine = adjustmentEngine,
        filterEngine = filterEngine,
        geometryEngine = geometryEngine,
        colorGradeEngine = colorGradeEngine,
        cutoutComposer = cutoutComposer,
        // Layers are composited *inside* the renderer, before the colour
        // stages, so filters and adjustments apply to the flattened stack
        // rather than only to the base photo.
        layerBitmaps = { layer -> layerStore.bitmapFor(layer) }
    )

    /**
     * Backing store for layer bitmaps.
     *
     * A byte-budgeted LRU, so holding ten layers costs a bounded amount of
     * memory instead of one full decode per layer.
     */
    private val layerStore = LayerStore()

    private val _preview = MutableStateFlow(PreviewState())
    val preview: StateFlow<PreviewState> = _preview.asStateFlow()

    /**
     * Bumped whenever [current] changes for any reason.
     *
     * A single chokepoint so autosave cannot be forgotten on one of the many
     * state-mutating paths. Counting changes rather than comparing whole states
     * keeps this cheap: a counter compare is free, whereas a deep equality walk
     * over every curve point and every layer would run per frame.
     */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private fun markChanged() {
        _revision.value = _revision.value + 1
    }

    /** The untouched decoded image. All rendering starts here. */
    private var original: Bitmap? = null

    private var current: EditState = EditState()

    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()

    private val renderMutex = Mutex()
    private var renderJob: Job? = null
    private val renderScope = CoroutineScope(Dispatchers.Default)

    /**
     * Set when state changed while a render was in flight.
     *
     * Guarded by [renderMutex] rather than being atomic: it is only ever
     * touched from the render loop and the scheduling path, and the loop
     * clears it before each pass.
     */
    @Volatile
    private var renderRequested = false

    private var lastRenderedBitmap: Bitmap? = null

    /**
     * The app-private copy of the open image.
     *
     * Kept because a project has to point at a file that still exists
     * tomorrow; the original content URI grant will not.
     */
    private var sourceFile: File? = null

    /** Id of the project this session is writing to, once one exists. */
    @Volatile
    var projectId: String? = null
        private set

    /** Display name shown in the projects grid. */
    @Volatile
    var projectName: String = "Untitled"
        private set

    // ------------------------------------------------------------- loading

    suspend fun open(context: android.content.Context, uri: Uri) {
        _preview.value = _preview.value.copy(isLoading = true, error = null)
        try {
            // ImageLoader copies out of the provider first, so a revoked
            // grant later cannot break this session.
            val loaded = ImageLoader.load(context, uri)
            original?.recycle()
            lastRenderedBitmap?.recycle()

            original = loaded.bitmap
            // ImageLoader already imported a private copy, so that file is
            // the one a project should point at.
            sourceFile = loaded.sourceFile
            current = EditState()
            undoStack.clear()
            redoStack.clear()
            projectId = null
            projectName = loaded.info.displayName

            _preview.value = PreviewState(
                bitmap = loaded.bitmap,
                original = loaded.bitmap,
                info = loaded.info,
                sourceUri = uri,
                isLoading = false,
                canUndo = false,
                canRedo = false,
                historyIndex = 0,
                historySize = 0
            )
        } catch (t: Throwable) {
            _preview.value = _preview.value.copy(
                isLoading = false,
                error = t.message ?: "Could not open that image"
            )
        }
    }

    /**
     * Closes the current image and returns to the picker.
     *
     * Nothing is recycled: with no edits applied, `state.bitmap` *is* the
     * original bitmap, and Compose may still be drawing it this frame.
     * Recycling here would crash with "trying to use a recycled bitmap", which
     * is the same class of bug the crop work hit. The GC reclaims it once the
     * UI drops the reference.
     */
    fun close() {
        renderJob?.cancel()
        renderJob = null
        renderRequested = false

        cutoutComposer?.invalidate()

        val wasOpen = original != null

        original = null
        lastRenderedBitmap = null
        current = EditState()
        undoStack.clear()
        redoStack.clear()
        editingTextId = null
        activeToolIsCrop = false
        cropCanvasKey = null
        cropCanvas = null
        sourceFile = null
        projectId = null

        if (wasOpen) {
            _preview.value = PreviewState()
        }
    }

    // --------------------------------------------------------- image layers

    /** The current layer stack, in draw order. */
    fun currentLayers(): List<ImageLayer> = current.layers

    val canAddLayer: Boolean get() = current.layers.size < ImageLayer.MAX_LAYERS

    val layerCount: Int get() = current.layers.size

    /**
     * Imports a layer image and adds it on top.
     *
     * The picked file is copied into app-private storage first, because the
     * picker's URI grant is temporary and the layer must still be readable at
     * export time.
     */
    suspend fun addLayer(context: android.content.Context, uri: Uri) {
        if (!canAddLayer) return
        try {
            val file = ImageStore.import(context, uri)
            val layer = ImageLayer(
                id = java.util.UUID.randomUUID().toString(),
                sourcePath = file.absolutePath
            )
            update({ it.copy(layers = it.layers + layer) }, "Add layer")
        } catch (t: Throwable) {
            _preview.value = _preview.value.copy(
                error = t.message ?: "Could not add that image"
            )
        }
    }

    /** Applies a transform change to one layer. */
    fun updateLayer(id: String, transform: (ImageLayer) -> ImageLayer, label: String) =
        update(
            { state ->
                state.copy(
                    layers = state.layers.map { if (it.id == id) transform(it) else it }
                )
            },
            label
        )

    /**
     * Snapshot taken when a transform gesture starts, or null when none is live.
     *
     * A drag fires on every pointer event, up to ~120 per second. Routing that
     * through [update] cost two things per event: a history entry, so one drag
     * buried the undo stack under hundreds of frames, and a full `EditState`
     * equality walk before the early-return check, which compares every
     * adjustment, curve point, colour grade and layer - the expensive part,
     * and the reason dragging lagged behind the finger.
     *
     * Live updates therefore mutate [current] directly, exactly like the
     * adjustment sliders already do, and [endTransformGesture] pushes a single
     * entry for the whole gesture when the finger lifts.
     */
    private var transformGestureStart: EditState? = null

    /**
     * Live transform of a layer during a drag or pinch.
     *
     * No history push and no equality walk: the gesture already knows the
     * value changed, because the finger moved.
     */
    fun setLayerTransformLive(id: String, x: Float, y: Float, scale: Float, degrees: Float) {
        if (transformGestureStart == null) {
            transformGestureStart = current
            interactiveGestureActive = true
        }
        endTextRun()
        current = current.copy(
            layers = current.layers.map {
                if (it.id == id) it.copy(offsetX = x, offsetY = y, scale = scale, rotationDegrees = degrees) else it
            }
        )
        markChanged()
        scheduleRender()
    }

    fun setTextTransformLive(id: String, x: Float, y: Float, fraction: Float, degrees: Float) {
        if (transformGestureStart == null) {
            transformGestureStart = current
            interactiveGestureActive = true
        }
        endTextRun()
        current = current.copy(
            textLayers = current.textLayers.map {
                if (it.id == id) {
                    it.copy(offsetX = x, offsetY = y, sizeFraction = fraction, rotationDegrees = degrees)
                } else {
                    it
                }
            }
        )
        markChanged()
        scheduleRender()
    }

    /** Collapses a whole gesture into one undo entry when the finger lifts. */
    fun endTransformGesture(label: String) {
        // Clear before pushing, so the full-quality frame is rendered straight
        // away rather than after the next unrelated change.
        if (interactiveGestureActive) {
            interactiveGestureActive = false
            scheduleRender()
        }
        val start = transformGestureStart ?: return
        transformGestureStart = null
        if (start == current) return
        undoStack.addLast(HistoryEntry(start, label))
        if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
    }

    fun setLayerScale(id: String, scale: Float) = updateLayer(
        id,
        { it.copy(scale = scale.coerceIn(0.05f, 3f)) },
        "Resize layer"
    )

    fun setLayerOffset(id: String, x: Float, y: Float) = updateLayer(
        id,
        { it.copy(offsetX = x, offsetY = y) },
        "Move layer"
    )

    fun setLayerRotation(id: String, degrees: Float) = updateLayer(
        id,
        { it.copy(rotationDegrees = ((degrees + 180f) % 360f) - 180f) },
        "Rotate layer"
    )

    fun setLayerOpacity(id: String, opacity: Float) = updateLayer(
        id,
        { it.copy(opacity = opacity.coerceIn(0f, 1f)) },
        "Layer opacity"
    )

    fun setLayerVisible(id: String, visible: Boolean) = updateLayer(
        id,
        { it.copy(visible = visible) },
        "Toggle layer"
    )

    fun resetLayer(id: String) = updateLayer(
        id,
        { LayerComposer.resetTransform(it) },
        "Reset layer"
    )

    /** Removes a layer and frees its cached bitmap. */
    fun removeLayer(id: String) {
        layerStore.evict(id)
        update({ it.copy(layers = it.layers.filterNot { l -> l.id == id }) }, "Remove layer")
    }

    /** Brings a layer to the front of the stack. */
    fun bringLayerToFront(id: String) = update({ state ->
        val target = state.layers.firstOrNull { it.id == id } ?: return@update state
        state.copy(layers = state.layers.filterNot { it.id == id } + target)
    }, "Bring to front")

    /** Sends a layer behind all the others. */
    fun sendLayerToBack(id: String) = update({ state ->
        val target = state.layers.firstOrNull { it.id == id } ?: return@update state
        state.copy(layers = listOf(target) + state.layers.filterNot { it.id == id })
    }, "Send to back")

    // ------------------------------------------------------- text layers

    val canAddText: Boolean get() = current.textLayers.size < TextLayer.MAX_TEXT_LAYERS

    val textCount: Int get() = current.textLayers.size

    fun addText(text: String = "Text"): String? {
        if (!canAddText) return null
        val id = java.util.UUID.randomUUID().toString()
        update(
            {
                it.copy(
                    textLayers = it.textLayers + TextLayer(
                        id = id,
                        text = text,
                        // Staggered so a second text does not land exactly on
                        // top of the first, which reads as "nothing happened".
                        offsetY = -0.25f + it.textLayers.size * 0.06f
                    )
                )
            },
            "Add text"
        )
        return id
    }

    fun updateText(id: String, transform: (TextLayer) -> TextLayer, label: String) = update(
        { state ->
            state.copy(
                textLayers = state.textLayers.map { if (it.id == id) transform(it) else it }
            )
        },
        label
    )

    /**
     * True while the previous history entry was also a text edit of this same
     * object, so the next one can replace it instead of stacking.
     */
    private var editingTextId: String? = null

    /**
     * Sets the text content, coalescing a whole typing burst into one undo step.
     *
     * Every keystroke used to go through [update], which pushes a history entry
     * per character. Typing "Apple" left five undos to back through, and
     * because each one also triggered its own render the whole word cost five
     * full pipeline passes.
     *
     * While the run is active - the same object, still typing - the newest
     * value simply replaces the top entry, so undo steps back over the entire
     * typed run at once and re-typing is cheap. The run ends as soon as a
     * different object or a different kind of edit comes through, which is
     * what stops an unrelated action being swallowed by the coalescing.
     */
    fun setTextContent(id: String, text: String) {
        val coalescing = editingTextId == id
        val next = current.copy(
            textLayers = current.textLayers.map { if (it.id == id) it.copy(text = text) else it }
        )
        if (next == current) return

        editingTextId = id
        if (coalescing && undoStack.isNotEmpty()) {
            // Replace the entry that this same run already pushed. `current`
            // has to move but the recorded "before" state must not, or undo
            // would land on a mid-word value.
            current = next
            markChanged()
            scheduleRender()
            return
        }

        undoStack.addLast(HistoryEntry(current, "Edit text"))
        if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()

        current = next
        markChanged()
        scheduleRender()
    }

    /**
     * Ends a typing run so the next edit starts a fresh history entry.
     *
     * Called from every path that changes something other than the text being
     * typed.
     */
    private fun endTextRun() {
        editingTextId = null
    }

    fun setTextSize(id: String, fraction: Float) = updateText(
        id,
        { it.copy(sizeFraction = fraction.coerceIn(0.01f, 0.6f)) },
        "Text size"
    )

    fun setTextOffset(id: String, x: Float, y: Float) = updateText(
        id,
        { it.copy(offsetX = x.coerceIn(-1f, 1f), offsetY = y.coerceIn(-1f, 1f)) },
        "Move text"
    )

    fun setTextRotation(id: String, degrees: Float) = updateText(
        id,
        { it.copy(rotationDegrees = ((degrees + 180f) % 360f) - 180f) },
        "Rotate text"
    )

    fun setTextColor(id: String, argb: Long) = updateText(
        id,
        { it.copy(color = argb) },
        "Text colour"
    )

    fun setTextFont(id: String, fontKey: String) = updateText(
        id,
        { it.copy(fontKey = fontKey) },
        "Text font"
    )

    fun setTextStyle(id: String, bold: Boolean? = null, italic: Boolean? = null) =
        updateText(
            id,
            {
                it.copy(
                    bold = bold ?: it.bold,
                    italic = italic ?: it.italic
                )
            },
            "Text style"
        )

    fun setTextOpacity(id: String, opacity: Float) = updateText(
        id,
        { it.copy(opacity = opacity.coerceIn(0f, 1f)) },
        "Text opacity"
    )

    fun setTextVisible(id: String, visible: Boolean) = updateText(
        id,
        { it.copy(visible = visible) },
        "Toggle text"
    )

    fun resetText(id: String) = updateText(
        id,
        {
            it.copy(
                offsetX = 0f,
                offsetY = 0f,
                rotationDegrees = 0f,
                sizeFraction = 0.06f
            )
        },
        "Reset text"
    )

    fun removeText(id: String) = update(
        { it.copy(textLayers = it.textLayers.filterNot { t -> t.id == id }) },
        "Remove text"
    )

    // ------------------------------------------------------------ draw layers

    /** True when anything has been drawn, so the panel can offer a clear-all. */
    val hasDrawing: Boolean
        get() = current.strokes.isNotEmpty() || current.fills.isNotEmpty()

    val strokeCount: Int get() = current.strokes.size

    /**
     * Appends a stroke.
     *
     * Called on every pointer *up*, not on every move: one drag is one undo
     * step, which is what makes undo behave the way a drawing app does. The
     * live preview comes from the in-progress stroke the stage holds locally,
     * so the user still sees the line follow their finger.
     */
    fun addStroke(stroke: DrawStroke) = update(
        { it.copy(strokes = it.strokes + stroke) },
        if (stroke.erase) "Erase" else "Draw"
    )

    /**
     * Extends the stroke currently being drawn.
     *
     * Mutates [current] directly rather than going through [update], because
     * this runs once per pointer event and a history entry per event would
     * both flood the undo stack and make a single drag impossible to undo.
     */
    fun appendStrokePoints(id: String, points: List<StrokePoint>) {
        if (points.isEmpty()) return
        val existing = current.strokes.firstOrNull { it.id == id } ?: return
        val merged = existing.copy(points = existing.points + points)
        endTextRun()
        current = current.copy(
            strokes = current.strokes.map { if (it.id == id) merged else it }
        )
        markChanged()
        scheduleRender()
    }

    /** Fills the region around a normalised point. */
    fun addFill(fill: DrawFill) = update(
        { it.copy(fills = it.fills + fill) },
        "Fill"
    )

    fun setFillColor(id: String, argb: Long) = update(
        { it.copy(fills = it.fills.map { if (it.id == id) it.copy(color = argb) else it }) },
        "Fill colour"
    )

    fun setStrokeVisible(id: String, visible: Boolean) = update(
        { it.copy(strokes = it.strokes.map { if (it.id == id) it.copy(visible = visible) else it }) },
        "Toggle stroke"
    )

    fun removeStroke(id: String) = update(
        { it.copy(strokes = it.strokes.filterNot { s -> s.id == id }) },
        "Remove stroke"
    )

    /** Removes every stroke and fill. One undo step. */
    fun clearDrawing() {
        if (!hasDrawing) return
        update({ it.copy(strokes = emptyList(), fills = emptyList()) }, "Clear drawing")
    }

    /**
     * Recolours every mark already drawn.
     *
     * Erase strokes are deliberately left alone: an eraser has no colour, and
     * "recolour" turning erases into painted lines would be the opposite of
     * what was asked for.
     */
    fun recolourDrawing(argb: Long) = update(
        {
            it.copy(
                strokes = it.strokes.map { s -> if (s.erase) s else s.copy(color = argb) },
                fills = it.fills.map { f -> f.copy(color = argb) }
            )
        },
        "Brush colour"
    )

    // ------------------------------------------------------- state changes

    /** Pushes a new state and re-renders. Used for discrete tool actions. */
    fun update(transform: (EditState) -> EditState, label: String) {
        val next = transform(current)
        if (next == current) return

        // Any non-typing edit closes an open typing run, so the next
        // keystroke cannot be coalesced into this action.
        endTextRun()

        undoStack.addLast(HistoryEntry(current, label))
        if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()

        current = next
        markChanged()
        scheduleRender()
    }

    /**
     * Updates an adjustment slider *without* pushing a history entry per frame;
     * the previous value is captured once when the gesture starts, so a whole
     * drag collapses into a single undo step.
     */
    fun beginSliderGesture() {
        if (sliderGestureStart == null) sliderGestureStart = current
    }

    fun updateAdjustmentLive(kind: AdjustmentKind, value: Float) {
        val base = sliderGestureStart?.adjustments ?: current.adjustments
        endTextRun()
        current = current.copy(
            adjustments = kind.with(base, value)
        )
        markChanged()
        scheduleRender()
    }

    fun endSliderGesture() {
        val start = sliderGestureStart ?: return
        sliderGestureStart = null
        if (start.adjustments != current.adjustments) {
            undoStack.addLast(HistoryEntry(start, "Adjust ${kindLabel}"))
            if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
            redoStack.clear()
        }
    }

    private var sliderGestureStart: EditState? = null
    private var lastTouchedKind: AdjustmentKind = AdjustmentKind.EXPOSURE

    fun setAdjustment(kind: AdjustmentKind, value: Float) {
        lastTouchedKind = kind
        if (sliderGestureStart == null) sliderGestureStart = current
        updateAdjustmentLive(kind, value)
    }

    fun commitAdjustmentGesture() = endSliderGesture()

    private val kindLabel: String get() = lastTouchedKind.label

    fun setFilter(id: String) = update({ it.copy(filterId = id) }, "Filter")

    fun setFilterStrength(v: Float) = update(
        { it.copy(filterStrength = v.coerceIn(0f, 1f)) }, "Filter strength"
    )

    fun rotate(steps: Int = 1) = update(
        { it.copy(rotationSteps = it.rotationSteps + steps) },
        "Rotate ${(steps * 90 + 360) % 360}Â°"
    )

    fun setStraighten(deg: Float) = update(
        { it.copy(straightenDegrees = deg.coerceIn(-45f, 45f)) }, "Straighten"
    )

    fun flip(axis: FlipAxis) = update(
        {
            when (axis) {
                FlipAxis.HORIZONTAL -> it.copy(flipH = !it.flipH)
                FlipAxis.VERTICAL -> it.copy(flipV = !it.flipV)
            }
        }, "Flip"
    )

    fun setCrop(rect: CropRect) = update({ it.copy(crop = rect) }, "Crop")

    /**
     * Crop drag support: [beginCropGesture] snapshots the state once, live
     * updates mutate it without touching history, and [endCropGesture] pushes a
     * single entry. That keeps a whole drag as one undo step.
     *
     * The baseline must be captured on the *first* live update when the UI did
     * not signal a gesture start, otherwise the baseline keeps being reset to
     * an already-dragged state and undo collapses to a no-op.
     */
    fun beginCropGesture() {
        if (cropGestureStart == null) cropGestureStart = current
    }

    fun updateCropLive(rect: CropRect) {
        if (cropGestureStart == null) cropGestureStart = current
        endTextRun()
        current = current.copy(crop = rect)
        markChanged()
        scheduleRender()
    }

    fun endCropGesture() {
        val start = cropGestureStart ?: return
        cropGestureStart = null
        if (start.crop != current.crop) {
            undoStack.addLast(HistoryEntry(start, "Crop"))
            if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
            redoStack.clear()
        }
    }

    private var cropGestureStart: EditState? = null

    fun setAspect(preset: AspectPreset) {
        val base = original ?: return

        // A 90/270 rotation swaps the effective dimensions, so the crop must be
        // computed against the post-rotation frame.
        val rotated = current.rotationSteps % 2 != 0
        val w = if (rotated) base.height else base.width
        val h = if (rotated) base.width else base.height

        // "Free" means no crop at all: clear it so the overlay returns to the
        // full frame rather than leaving a stale rectangle behind.
        if (preset.ratio == null) {
            update({ it.copy(crop = null) }, "Free crop")
            return
        }

        val newCrop = cropForSize(w, h, preset.ratio)
        update({ it.copy(crop = newCrop) }, "Aspect ${preset.label}")
    }

    /** Largest centred crop of the given ratio, without allocating a bitmap. */
    private fun cropForSize(w: Int, h: Int, ratio: Float): CropRect {
        val current = w.toFloat() / h.toFloat()
        return if (current > ratio) {
            val newW = (h * ratio).toInt().coerceIn(1, w)
            val left = (w - newW) / 2
            CropRect(left, 0, left + newW, h)
        } else {
            val newH = (w / ratio).toInt().coerceIn(1, h)
            val top = (h - newH) / 2
            CropRect(0, top, w, top + newH)
        }
    }

    fun setResize(width: Int, height: Int) = update(
        { it.copy(resizeWidth = width, resizeHeight = height) }, "Resize"
    )

    fun resetAll() {
        if (current.isDefault) return
        undoStack.addLast(HistoryEntry(current, "Reset"))
        redoStack.clear()
        current = EditState()
        markChanged()
        scheduleRender()
    }

    fun resetAdjustments() = update({ it.copy(adjustments = Adjustments.NEUTRAL) }, "Reset adjustments")

    // ------------------------------------------------- Phase 2: colour

    fun currentColorGrade(): ColorGrade = current.colorGrade

    fun setColorGrade(grade: ColorGrade, label: String) =
        update({ it.copy(colorGrade = grade) }, label)

    /** Sets one channel's tone curve. */
    fun setCurve(channel: CurveChannel, points: List<CurvePoint>) =
        update({ it.withCurve(channel, points) }, "${channel.label} curve")

    /** Resets a channel's curve back to a straight line. */
    fun resetCurve(channel: CurveChannel) =
        update(
            { it.withCurve(channel, ToneCurve.DEFAULT_POINTS) },
            "Reset ${channel.label} curve"
        )

    fun setLevels(channel: CurveChannel, levels: Levels) =
        update({ it.withLevels(channel, levels) }, "${channel.label} levels")

    fun resetLevels(channel: CurveChannel) =
        update({ it.withLevels(channel, Levels()) }, "Reset ${channel.label} levels")

    fun setHsl(adj: HslAdjustments) =
        update({ it.copy(colorGrade = it.colorGrade.copy(hsl = adj)) }, "HSL")

    fun setSelectiveColor(sc: SelectiveColor) =
        update(
            { it.copy(colorGrade = it.colorGrade.copy(selectiveColor = sc)) },
            "Selective colour"
        )

    fun resetColorGrade() =
        update({ it.copy(colorGrade = ColorGrade()) }, "Reset colour")

    // -------------------------------------------- Phase 2: live gestures

    /**
     * Baseline for a colour drag.
     *
     * Curve points and HSL sliders emit a value on every touch frame. Pushing a
     * history entry each time would flood the 50-deep undo stack with a single
     * drag, so like the Phase 1 sliders these snapshot once and commit once.
     */
    private var colorGestureStart: EditState? = null
    private var colorGestureLabel: String = "Colour"

    fun beginColorGesture(label: String) {
        if (colorGestureStart == null) {
            colorGestureStart = current
            colorGestureLabel = label
        }
    }

    private fun liveColor(transform: (ColorGrade) -> ColorGrade) {
        val base = colorGestureStart ?: current
        val next = current.copy(colorGrade = transform(base.colorGrade))
        if (next == current) return
        current = next
        markChanged()
        scheduleRender()
    }

    fun endColorGesture() {
        val start = colorGestureStart ?: return
        colorGestureStart = null
        if (start.colorGrade != current.colorGrade) {
            undoStack.addLast(HistoryEntry(start, colorGestureLabel))
            if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
            redoStack.clear()
        }
    }

    fun updateCurveLive(channel: CurveChannel, points: List<CurvePoint>) =
        liveColor { it.copy(curves = it.curves + (channel to ToneCurve(channel, points))) }

    fun updateLevelsLive(channel: CurveChannel, levels: Levels) =
        liveColor { it.copy(levels = it.levels + (channel to levels)) }

    fun updateHslLive(adj: HslAdjustments) =
        liveColor { it.copy(hsl = adj) }

    fun updateSelectiveColorLive(sc: SelectiveColor) =
        liveColor { it.copy(selectiveColor = sc) }

    private fun EditState.withCurve(
        channel: CurveChannel,
        points: List<CurvePoint>
    ): EditState = copy(
        colorGrade = colorGrade.copy(
            curves = colorGrade.curves + (channel to ToneCurve(channel, points))
        )
    )

    private fun EditState.withLevels(
        channel: CurveChannel,
        levels: Levels
    ): EditState = copy(
        colorGrade = colorGrade.copy(
            levels = colorGrade.levels + (channel to levels)
        )
    )

    // -------------------------------------------- "tap to select" cutout

    /** True when the MediaPipe model is present and usable. */
    val cutoutAvailable: Boolean get() = cutoutComposer != null

    /**
     * Adds a tap to the selection.
     *
     * The first tap starts a new selection; later taps extend it, so tapping a
     * person and then their bag keeps both. [replace] forces a new selection
     * instead, which is what the "New selection" button uses.
     */
    fun addTap(x: Float, y: Float, replace: Boolean = false) {
        val existing = current.cutout
        val anchors = if (replace) {
            listOf(TapAnchor(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)))
        } else {
            existing.anchors + TapAnchor(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        }
        cutoutComposer?.invalidate()
        update(
            { it.copy(cutout = it.cutout.copy(anchors = anchors)) },
            if (replace) "Select object" else "Add to selection"
        )
    }

    /** Clears the selection and restores the original background. */
    fun clearCutout() {
        if (!current.cutout.isActive) return
        cutoutComposer?.invalidate()
        update({ it.copy(cutout = CutoutState()) }, "Clear selection")
    }

    fun setCutoutKeepSubject(keep: Boolean) =
        update({ it.copy(cutout = it.cutout.copy(keepSubject = keep)) }, "Keep subject")

    fun setCutoutThreshold(value: Float) = update(
        { it.copy(cutout = it.cutout.copy(threshold = value.coerceIn(0.05f, 0.95f))) },
        "Cutout threshold"
    )

    fun setCutoutFeather(value: Float) = update(
        { it.copy(cutout = it.cutout.copy(feather = value.coerceIn(0f, 0.4f))) },
        "Edge softness"
    )

    // ------------------------------------------------------------- history

    fun undo() {
        endTextRun()
        val entry = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(HistoryEntry(current, entry.label))
        current = entry.state
        markChanged()
        scheduleRender()
    }

    fun redo() {
        endTextRun()
        val entry = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(HistoryEntry(current, entry.label))
        current = entry.state
        markChanged()
        scheduleRender()
    }

    /**
     * Jumps to a point in the undo timeline. Everything after the target is
     * moved onto the redo stack so history stays navigable in both directions.
     */
    fun jumpTo(timelineIndex: Int) {
        endTextRun()
        val targetEntry = undoStack.getOrNull(timelineIndex) ?: return
        val targetState = targetEntry.state

        while (undoStack.size > timelineIndex) {
            val popped = undoStack.removeLast()
            redoStack.addLast(HistoryEntry(popped.state, popped.label))
        }

        current = targetState
        markChanged()
        scheduleRender()
    }

    // ------------------------------------------------------------ rendering

    private fun scheduleRender() {
        // Coalesce rather than cancel-and-restart.
        //
        // Cancelling mid-render threw away a partially finished frame and
        // started the whole pipeline again, so a fast drag could starve the
        // pipeline completely and the preview would only catch up once the
        // finger stopped. Instead, at most one render runs and any request
        // that arrives while it is busy sets a flag that triggers exactly one
        // more pass on the newest state.
        renderRequested = true
        if (renderJob?.isActive == true) return
        renderJob = renderScope.launch { drainRenders() }
    }

    /**
     * Renders until no further state changes are pending.
     *
     * Each pass re-reads [current], so several slider updates that arrived
     * mid-render collapse into a single follow-up frame on the latest values.
     */
    private suspend fun drainRenders() {
        while (renderRequested) {
            renderRequested = false
            render()
        }
    }

    /**
     * Opens a saved project, restoring both the image and the whole edit.
     *
     * The state is assigned directly rather than replayed through [update], so
     * reopening a project does not push a dozen undo entries or leave the
     * redo stack littered.
     */
    suspend fun openProject(
        context: android.content.Context,
        project: Project
    ) {
        _preview.value = _preview.value.copy(isLoading = true, error = null)
        try {
            val file = File(project.sourcePath)
            val loaded = ImageLoader.loadFromFile(context, file, project.displayName)
            original?.recycle()
            lastRenderedBitmap?.recycle()

            original = loaded.bitmap
            sourceFile = file
            current = project.state
            undoStack.clear()
            redoStack.clear()
            editingTextId = null
            projectId = project.id
            projectName = project.displayName
            cropSessionReset = true

            _preview.value = PreviewState(
                bitmap = loaded.bitmap,
                original = loaded.bitmap,
                info = loaded.info,
                isLoading = false,
                layers = current.layers
            )
            scheduleRender()
        } catch (t: Throwable) {
            _preview.value = _preview.value.copy(
                isLoading = false,
                error = t.message ?: "That project could not be opened"
            )
        }
    }

    /** Set when a restore replaced the crop session held by the ViewModel. */
    @Volatile
    var cropSessionReset = false
        private set

    /**
     * Opens a generated file as the working image.
     *
     * Used for a blank canvas, which is a real image on disk rather than a
     * flag on the state. Going through [openProject]-style loading keeps a
     * blank project on the identical code path as a picked photo, so saving,
     * restoring, layering and exporting all behave the same.
     */
    suspend fun adoptBlankSource(
        context: android.content.Context,
        file: File,
        displayName: String
    ) {
        _preview.value = _preview.value.copy(isLoading = true, error = null)
        try {
            val loaded = ImageLoader.loadFromFile(context, file, displayName)
            original?.recycle()
            lastRenderedBitmap?.recycle()

            original = loaded.bitmap
            sourceFile = file
            current = EditState()
            undoStack.clear()
            redoStack.clear()
            projectId = null
            projectName = displayName
            cropSessionReset = true

            _preview.value = PreviewState(
                bitmap = loaded.bitmap,
                original = loaded.bitmap,
                info = loaded.info,
                isLoading = false
            )
            scheduleRender()
        } catch (t: Throwable) {
            _preview.value = _preview.value.copy(
                isLoading = false,
                error = t.message ?: "Could not start a new project"
            )
        }
    }

    /** Forces a re-render, e.g. when the tool changes what it needs. */
    fun refresh() = scheduleRender()

    /**
     * Persists the current edit as a project.
     *
     * Called on a debounce after every state change, so a crash or an
     * out-of-memory kill at worst loses the last few seconds of work rather
     * than the whole edit.
     */
    suspend fun autosave(context: android.content.Context) {
        val file = sourceFile ?: return
        val state = current
        if (state.isDefault) {
            // Back to a pristine image: the project has nothing to hold.
            projectId?.let { ProjectStore.delete(context, it) }
            projectId = null
            return
        }

        val preview = _preview.value.bitmap
        val id = ProjectStore.save(context, projectId, file, projectName, state) ?: return
        projectId = id

        // Thumbnail once the first render lands, not on every keystroke.
        if (preview != null && _preview.value.thumbnailMissing) {
            ProjectStore.writeThumbnail(context, id, preview)
            _preview.value = _preview.value.copy(thumbnailMissing = false)
        }
    }

    suspend fun deleteProject(context: android.content.Context, id: String) {
        ProjectStore.delete(context, id)
        if (projectId == id) projectId = null
    }

    /** Forgets the current project without deleting it, e.g. on "new photo". */
    fun detachProject() {
        projectId = null
    }

    /** Renders the current state at full resolution for export. */
    suspend fun renderFull(): Bitmap? = withContext(renderDispatcher) {
        val base = original ?: return@withContext null
        renderMutex.withLock { pipeline(base, current, previewSize = null) }
    }

    private suspend fun render() {
        val base = original ?: return
        renderMutex.withLock {
            _preview.value = _preview.value.copy(isRendering = true)

            val state = current
            // A smaller frame while the finger is down, for the reason given
            // on [interactiveGestureActive].
            val edge = if (interactiveGestureActive) GESTURE_PREVIEW_EDGE else PREVIEW_EDGE
            val result = pipeline(base, state, previewSize = edge)

            // While cropping, the overlay must sit on the *uncropped* image.
            // If it drew on `result` the crop rect would be applied twice and
            // the frame would drift out of alignment with the photo.
            val cropSource = when (activeToolIsCrop) {
                true -> cachedGeometryPreview(base, state)
                else -> null
            }

            _preview.value = _preview.value.copy(
                bitmap = result,
                cropBase = cropSource,
                layers = state.layers,
                textLayers = state.textLayers,
                strokes = state.strokes,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                historyIndex = undoStack.size,
                historySize = undoStack.size,
                isRendering = false
            )
        }
    }

    /**
     * Geometry-only preview used as the crop canvas: rotated, flipped and
     * straightened, but not yet cropped, and not colour-graded (so the crop
     * frame is judged against the true geometry).
     */
    private suspend fun geometryPreview(base: Bitmap, state: EditState): Bitmap? =
        withContext(renderDispatcher) {
            var img = downscale(base, PREVIEW_EDGE)
            if (state.rotationSteps % 4 != 0) {
                img = geometryEngine.rotate(img, state.rotationSteps * 90)
            }
            if (state.straightenDegrees != 0f) {
                img = geometryEngine.straightenAndTrim(img, state.straightenDegrees)
            }
            if (state.flipH) img = geometryEngine.flip(img, FlipAxis.HORIZONTAL)
            if (state.flipV) img = geometryEngine.flip(img, FlipAxis.VERTICAL)
            img
        }

    /**
     * Memoised [geometryPreview].
     *
     * The crop canvas depends only on rotation, straighten and flip — never on
     * the crop rect itself. Dragging a crop edge therefore produced an
     * identical canvas dozens of times per gesture, each one a full rotate /
     * flip / allocate pass on top of the real pipeline. Caching on the
     * geometry signature means the drag only pays for the pipeline once.
     */
    private suspend fun cachedGeometryPreview(base: Bitmap, state: EditState): Bitmap? {
        val key = "${state.rotationSteps}|${state.straightenDegrees}|${state.flipH}|${state.flipV}"
        if (key == cropCanvasKey && cropCanvas != null) return cropCanvas

        val rendered = geometryPreview(base, state)
        cropCanvasKey = key
        cropCanvas = rendered
        return rendered
    }

    private var cropCanvasKey: String? = null
    private var cropCanvas: Bitmap? = null

    private fun downscale(src: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = maxOf(src.width, src.height)
        if (longEdge <= maxEdge) return src
        val ratio = maxEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            src,
            (src.width * ratio).toInt().coerceAtLeast(1),
            (src.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    /** Set by the UI so the renderer knows to produce a crop canvas. */
    @Volatile
    var activeToolIsCrop: Boolean = false

    /**
     * Preview edge used while a transform gesture is live.
     *
     * Dragging an object used to re-run the entire pipeline at the full preview
     * size on *every pointer event* - up to 120 times a second. Each pass
     * allocates and copies a full ARGB_8888 frame (about 8 MB at 1440px) plus
     * the compositing layers, so the cost was dominated by allocation and
     * memory traffic rather than by any arithmetic. The finger therefore
     * waited on the pipeline and the object visibly trailed it.
     *
     * Dropping to a smaller edge during the gesture cuts that allocation by
     * roughly an order of magnitude. It is a deliberate trade: the frame is
     * momentarily softer *while the finger is down*, and snaps back to full
     * sharpness the instant it lifts. Nothing is baked in - the final render
     * always runs at full quality, so the exported image is unaffected.
     */
    @Volatile
    var interactiveGestureActive: Boolean = false

    /**
     * The full non-destructive pipeline.
     *
     * Delegates to [PipelineRenderer], which runs every pixel stage against a
     * single shared buffer. This replaced an earlier implementation that
     * allocated one IntArray per stage, which caused noticeable lag when
     * dragging sliders on large photos.
     */
    private suspend fun pipeline(
        base: Bitmap,
        state: EditState,
        previewSize: Int?
    ): Bitmap? = withContext(renderDispatcher) {
        // Geometry, cutout, layer compositing, then the colour stages. The
        // renderer owns the whole order; nothing is composited here, because
        // anything added after the colour stages would escape the filter and
        // the adjustment sliders.
        val result = renderer.render(base, state, previewSize)

        if (previewSize != null && result != null) {
            // Deliberately do NOT recycle the outgoing preview. Compose still
            // holds the bitmap it is currently drawing, and freeing it
            // underneath the UI crashes with "trying to use a recycled
            // bitmap". The GC reclaims it once Compose drops the reference.
            lastRenderedBitmap = result
        }
        result
    }

    private suspend fun applyCrop(
        img: Bitmap,
        rect: CropRect
    ): Bitmap? {
        val clamped = rect.clampTo(img.width, img.height)
        if (clamped.width == img.width && clamped.height == img.height &&
            clamped.left == 0 && clamped.top == 0
        ) return null
        return geometryEngine.crop(img, clamped)
    }

    /**
     * Width of the frame after rotation, in FULL-RESOLUTION pixels.
     * This is the coordinate space crop rects are stored in.
     */
    private fun geometryWidth(base: Bitmap, state: EditState): Int {
        val swapped = state.rotationSteps % 2 != 0
        val w = if (swapped) base.height else base.width
        return if (state.straightenDegrees != 0f) {
            // Straightening changes the real bounds; approximate with the
            // same math GeometryEngine.straightenAndTrim ends up using.
            val rad = Math.toRadians(state.straightenDegrees.toDouble())
            val c = kotlin.math.abs(kotlin.math.cos(rad))
            val s = kotlin.math.abs(kotlin.math.sin(rad))
            val swappedBack = if (swapped) base.width else base.height
            ((w * c + swappedBack * s).toInt()).coerceAtLeast(1)
        } else {
            w
        }
    }

    /** Full-resolution width of the post-geometry frame (crop coord space). */
    fun geometryWidthForCrop(): Int {
        val base = original ?: return 0
        return geometryWidth(base, current)
    }

    // -------------------------------------------------------------- export

    /** Renders at full resolution for saving. */
    suspend fun renderForExport(): Bitmap? = renderFull()

    fun currentState(): EditState = current

    fun release() {
        original?.recycle()
        lastRenderedBitmap?.recycle()
        original = null
        lastRenderedBitmap = null
    }

    private companion object {
        const val MAX_HISTORY = 50
        const val PREVIEW_EDGE = 1440

        /**
         * Preview edge during a drag or pinch.
         *
         * 720 rather than something more aggressive: below roughly 600 the
         * photo becomes too soft to aim with, and aiming badly is worse than
         * a slightly soft frame.
         */
        const val GESTURE_PREVIEW_EDGE = 720
    }
}
