package com.alex.photoeditor.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alex.photoeditor.R
import com.alex.photoeditor.ui.EditorTool
import com.alex.photoeditor.ui.EditorViewModel
import com.alex.photoeditor.ui.rememberSafeArea
import com.alex.photoeditor.ui.components.AdjustPanel
import com.alex.photoeditor.ui.components.CropStage
import com.alex.photoeditor.ui.components.DrawGestureStage
import com.alex.photoeditor.ui.components.DrawPanel
import com.alex.photoeditor.ui.components.ExpressiveButton
import com.alex.photoeditor.ui.components.ExpressiveIconToggle
import com.alex.photoeditor.ui.components.ExpressiveTonalButton
import com.alex.photoeditor.ui.components.pressAndHold
import com.alex.photoeditor.ui.components.ExportSheet
import com.alex.photoeditor.ui.components.FilterPanel
import com.alex.photoeditor.ui.components.InfoPanel
import com.alex.photoeditor.ui.components.LayerGestureStage
import com.alex.photoeditor.ui.components.LayersPanel
import com.alex.photoeditor.ui.components.NewProjectSheet
import com.alex.photoeditor.ui.components.ProjectsSheet
import com.alex.photoeditor.ui.components.TapSelectPanel
import com.alex.photoeditor.ui.components.TextGestureStage
import com.alex.photoeditor.ui.components.TextPanel
import com.alex.photoeditor.ui.components.ToolRail
import com.alex.photoeditor.ui.components.TransformPanel

@Composable
fun EditorScreen(
    vm: EditorViewModel,
    onPickImage: () -> Unit,
    onPickLayer: () -> Unit = {}
) {
    val state by vm.preview.collectAsStateWithLifecycle()

    // The window is edge-to-edge, so the notch and the navigation bar overlap
    // the content unless the safe area is applied here.
    val safeArea = rememberSafeArea()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(
                top = safeArea.top,
                bottom = safeArea.bottom,
                start = safeArea.start,
                end = safeArea.end
            )
    ) {
        when {
            state.error != null -> ErrorState(state.error!!, onPickImage)
            state.bitmap == null -> EmptyState(onPickImage, vm::openProjects)
            else -> EditorContent(
                vm = vm,
                state = state,
                onPickLayer = onPickLayer
            )
        }

        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    if (vm.showExportSheet) {
        ExportSheet(vm = vm)
    }

    if (vm.showProjects) {
        ProjectsSheet(vm = vm)
    }

    // Drawn after the projects sheet so the chooser sits on top of it.
    if (vm.showNewProject) {
        NewProjectSheet(vm = vm, onPickImage = onPickImage)
    }
}

@Composable
private fun EditorContent(
    vm: EditorViewModel,
    state: com.alex.photoeditor.core.PreviewState,
    onPickLayer: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {

        TopBar(vm = vm, state = state)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // Pinned dark regardless of the system theme. The surrounding
                // chrome follows the user's wallpaper palette, but judging
                // exposure or colour against a light or strongly tinted stage
                // biases perception, which is not acceptable in a photo tool.
                .background(Color(0xFF101014)),
            contentAlignment = Alignment.Center
        ) {
            // Press-and-hold compares against the untouched original.
            val isCropMode = vm.activeTool == EditorTool.CROP
            val isLayerMode = vm.activeTool == EditorTool.LAYERS
            val isTextMode = vm.activeTool == EditorTool.TEXT
            val isDrawMode = vm.activeTool == EditorTool.DRAW

            // The layer stack is taken from the *collected* state, not from
            // vm.currentLayers(). Reading a StateFlow's value directly does not
            // register a snapshot read, so a composable that did so only ever
            // recomposed when some unrelated Compose state changed - which is
            // why the eye and delete controls appeared to need the tool
            // reopening before they showed anything.
            val activeLayer = state.layers.firstOrNull { it.id == vm.activeLayerId }
                ?: state.layers.lastOrNull()

            // Taken from the collected state for the same reason as the image
            // stack: reading the repository directly would not register a
            // snapshot read and the stage would never appear.
            val activeText = state.textLayers.firstOrNull { it.id == vm.activeTextId }
                ?: state.textLayers.lastOrNull()

            val shown = when {
                vm.isComparing -> state.original
                // Cropping draws on the uncropped geometry render so the crop
                // rect is not applied twice.
                isCropMode -> state.cropBase ?: state.bitmap
                else -> state.bitmap
            }
            shown?.let { bmp ->
                if (isCropMode) {
                    // Interactive crop: the overlay must sit on the exact
                    // rendered image, so CropStage handles the letterboxing.
                    CropStage(
                        bitmap = bmp,
                        initial = vm.activeCropSelection(),
                        onPreviewChange = vm::updateCrop,
                        onCommit = vm::commitCrop
                    )
                } else if (isLayerMode && activeLayer != null) {
                    // The stacked image is dragged and pinched directly on the
                    // photo, so the gesture stage takes over the preview.
                    LayerGestureStage(
                        bitmap = bmp,
                        layer = activeLayer,
                        vm = vm
                    )
                } else if (isTextMode && activeText != null) {
                    // Same gesture contract as an image layer, so moving
                    // between a caption and a sticker feels identical. The
                    // eyedropper borrows the same stage: with it on, a tap
                    // samples the flattened preview instead of moving.
                    TextGestureStage(
                        bitmap = bmp,
                        layer = activeText,
                        vm = vm,
                        eyedropperActive = vm.eyedropperActive
                    )
                } else if (isDrawMode) {
                    // Drawing borrows the stage the same way text does: the
                    // surface is the photo itself, so a stroke has to land
                    // where the finger is rather than on a separate canvas.
                    DrawGestureStage(
                        bitmap = bmp,
                        vm = vm
                    )
                } else {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Edited photo preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .semantics { contentDescription = "Photo preview" }
                    )
                }
            }

            if (state.isRendering) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(18.dp)
                )
            }
        }

        ToolRail(
            active = vm.activeTool,
            onSelect = vm::selectTool,
            collapsed = vm.panelCollapsed,
            onToggleCollapsed = vm::togglePanelCollapsed,
            modifier = Modifier.fillMaxWidth()
        )

        // Collapsed means the panel is gone entirely, not merely shorter. The
        // rail's own arrow brings it back, so the preview gets the full
        // remaining height - which is the entire point of the control.
        if (vm.panelCollapsed) return@Column

        // The tool panel is height-bounded and scrollable rather than taking
        // its natural height.
        //
        // The preview above carries weight(1f), so it absorbs whatever the
        // panel does not claim. Text has more controls than Add image (a
        // field, fonts, styles, a colour picker), so its panel pushed the
        // preview smaller and the photo rendered at a different size depending
        // on which tool happened to be open - which is what made the two tools
        // feel like they were not the same screen. Capping the panel keeps the
        // preview identical across every tool and moves the overflow into a
        // scroll, which is the honest place for it.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val panelMaxHeight = maxHeight * 0.45f
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = panelMaxHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                when (vm.activeTool) {
                    EditorTool.ADJUST -> AdjustPanel(vm)
                    EditorTool.FILTERS -> FilterPanel(vm)
                    EditorTool.CROP -> TransformPanel(vm, cropMode = true)
                    EditorTool.TRANSFORM -> TransformPanel(vm, cropMode = false)
                    // The tap targets the fully rendered image, including any
                    // cutout, so the user sees the selection as they make it.
                    // Using the geometry-only render here meant the stage
                    // showed an uncut photo and the removal only appeared after
                    // switching tools.
                    EditorTool.TAP_SELECT -> TapSelectPanel(vm, state.bitmap)
                    EditorTool.LAYERS -> LayersPanel(
                        vm = vm,
                        layers = state.layers,
                        onPickLayer = onPickLayer
                    )
                    EditorTool.TEXT -> TextPanel(
                        vm = vm,
                        texts = state.textLayers,
                        eyedropperOn = vm.eyedropperActive,
                        onEyedropperChange = vm::setEyedropper,
                        onAddText = { vm.addText() }
                    )
                    EditorTool.INFO -> InfoPanel(state.info)
                    EditorTool.DRAW -> DrawPanel(vm = vm)
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    vm: EditorViewModel,
    state: com.alex.photoeditor.core.PreviewState
) {
    val comparing = vm.isComparing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            // Matches the system Back button: step out of the editor rather
            // than wiping every edit. Reverting is already covered by undo, and
            // a lone back arrow silently destroying the edit was surprising.
            onClick = { vm.closeImage() },
            modifier = Modifier.semantics { contentDescription = "Close photo" }
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                "Close",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        IconButton(
            onClick = vm::undo,
            enabled = state.canUndo,
            modifier = Modifier.semantics { contentDescription = "Undo" }
        ) {
            Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
        }

        IconButton(
            onClick = vm::redo,
            enabled = state.canRedo,
            modifier = Modifier.semantics { contentDescription = "Redo" }
        ) {
            Icon(Icons.AutoMirrored.Filled.Redo, "Redo")
        }

        // Hold to see the original. A filled tonal state while held makes the
        // "you are looking at the unedited photo" moment unambiguous.
        ExpressiveIconToggle(
            icon = Icons.Filled.Compare,
            contentDescription = "Hold to compare with original",
            onClick = {},
            selected = comparing,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .size(44.dp)
                .pressAndHold(
                    onDown = { vm.toggleComparing(true) },
                    onUp = { vm.toggleComparing(false) }
                )
        )

        Spacer(Modifier.weight(1f))

        state.info?.let {
            Text(
                text = "${it.widthPx}x${it.heightPx}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
        }

        IconButton(
            onClick = { vm.share() },
            modifier = Modifier.semantics { contentDescription = "Share" }
        ) {
            Icon(Icons.Filled.Share, "Share")
        }

        IconButton(
            onClick = vm::openExportSheet,
            modifier = Modifier.semantics { contentDescription = "Save to gallery" }
        ) {
            Icon(Icons.Filled.Download, "Save", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun EmptyState(onPickImage: () -> Unit, onOpenProjects: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // The real app mark rather than a generic sliders glyph, so the first
        // screen is already recognisably the app. This is a flattened vector
        // rather than R.mipmap.ic_launcher: the launcher icon is an adaptive-
        // icon XML, which painterResource cannot load.
        Image(
            painter = painterResource(id = R.drawable.ic_brand),
            contentDescription = null,
            modifier = Modifier.size(96.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Photo Editor by Alex",
            style = MaterialTheme.typography.titleLarge,
            // Explicitly on-surface rather than the default: the title sits on
            // the near-black background, where the inherited colour from the
            // picker surface read as grey and looked disabled.
            color = Color.White
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Choose a photo to start editing. Everything stays on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        ExpressiveButton(
            text = "Open photo",
            icon = Icons.Filled.AddPhotoAlternate,
            onClick = onPickImage
        )
        Spacer(Modifier.height(10.dp))
        ExpressiveTonalButton(
            text = "Projects",
            icon = Icons.Filled.Folder,
            onClick = onOpenProjects
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Edits save automatically, so nothing is lost if you close the app.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.OutlinedButton(onClick = onRetry) {
            Text("Try another photo")
        }
    }
}
