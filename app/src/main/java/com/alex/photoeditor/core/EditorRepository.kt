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

/**
 * The complete, serialisable description of an edit.
 *
 * Nothing here holds pixels â€” only the original bitmap is retained, and every
 * render is derived from it. That is what makes the editor non-destructive:
 * changing any value simply re-derives the preview from [original].
 */
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
    val colorGrade: ColorGrade = ColorGrade()
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
            colorGrade.isNeutral

    /** Human-readable summary for the history panel. */
    fun describe(): String = buildList {
        if (rotationSteps != 0) add("Rotate ${(rotationSteps * 90) % 360}Â°")
        if (straightenDegrees != 0f) add("Straighten ${"%.1f".format(straightenDegrees)}Â°")
        if (flipH) add("Flip horizontal")
        if (flipV) add("Flip vertical")
        if (crop != null) add("Crop")
        if (resizeWidth != null) add("Resize")
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
    val compareHeld: Boolean = false
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
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    /**
     * Runs the pixel stack. Created lazily so it reuses the same engines this
     * repository already holds, rather than instantiating duplicate ones.
     */
    private val renderer = PipelineRenderer(
        adjustmentEngine = adjustmentEngine,
        filterEngine = filterEngine,
        geometryEngine = geometryEngine,
        colorGradeEngine = colorGradeEngine
    )

    private val _preview = MutableStateFlow(PreviewState())
    val preview: StateFlow<PreviewState> = _preview.asStateFlow()

    /** The untouched decoded image. All rendering starts here. */
    private var original: Bitmap? = null

    private var current: EditState = EditState()

    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()

    private val renderMutex = Mutex()
    private var renderJob: Job? = null
    private val renderScope = CoroutineScope(Dispatchers.Default)

    private var lastRenderedBitmap: Bitmap? = null

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
            current = EditState()
            undoStack.clear()
            redoStack.clear()

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

    // ------------------------------------------------------- state changes

    /** Pushes a new state and re-renders. Used for discrete tool actions. */
    fun update(transform: (EditState) -> EditState, label: String) {
        val next = transform(current)
        if (next == current) return

        undoStack.addLast(HistoryEntry(current, label))
        if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()

        current = next
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
        current = current.copy(
            adjustments = kind.with(base, value)
        )
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
        current = current.copy(crop = rect)
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

    // ------------------------------------------------------------- history

    fun undo() {
        val entry = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(HistoryEntry(current, entry.label))
        current = entry.state
        scheduleRender()
    }

    fun redo() {
        val entry = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(HistoryEntry(current, entry.label))
        current = entry.state
        scheduleRender()
    }

    /**
     * Jumps to a point in the undo timeline. Everything after the target is
     * moved onto the redo stack so history stays navigable in both directions.
     */
    fun jumpTo(timelineIndex: Int) {
        val targetEntry = undoStack.getOrNull(timelineIndex) ?: return
        val targetState = targetEntry.state

        while (undoStack.size > timelineIndex) {
            val popped = undoStack.removeLast()
            redoStack.addLast(HistoryEntry(popped.state, popped.label))
        }

        current = targetState
        scheduleRender()
    }

    // ------------------------------------------------------------ rendering

    private fun scheduleRender() {
        renderJob?.cancel()
        renderJob = renderScope.launch { render() }
    }

    /** Forces a re-render, e.g. when the tool changes what it needs. */
    fun refresh() = scheduleRender()

    /** Renders the current state at full resolution for export. */
    suspend fun renderFull(): Bitmap? = withContext(renderDispatcher) {
        val base = original ?: return@withContext null
        renderMutex.withLock { pipeline(base, current, previewSize = null) }
    }

    private suspend fun render() {
        val base = original ?: return
        renderMutex.withLock {
            _preview.value = _preview.value.copy(isRendering = true)

            val result = pipeline(base, current, previewSize = PREVIEW_EDGE)

            // While cropping, the overlay must sit on the *uncropped* image.
            // If it drew on `result` the crop rect would be applied twice and
            // the frame would drift out of alignment with the photo.
            val cropSource = when (activeToolIsCrop) {
                true -> geometryPreview(base, current)
                else -> null
            }

            _preview.value = _preview.value.copy(
                bitmap = result,
                cropBase = cropSource,
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
    }
}
