package com.alex.photoeditor.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Compare
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alex.photoeditor.ui.EditorTool
import com.alex.photoeditor.ui.EditorViewModel
import com.alex.photoeditor.ui.components.AdjustPanel
import com.alex.photoeditor.ui.components.CropStage
import com.alex.photoeditor.ui.components.pressAndHold
import com.alex.photoeditor.ui.components.ExportSheet
import com.alex.photoeditor.ui.components.FilterPanel
import com.alex.photoeditor.ui.components.InfoPanel
import com.alex.photoeditor.ui.components.ToolRail
import com.alex.photoeditor.ui.components.TransformPanel
import com.alex.photoeditor.ui.theme.Surface1

@Composable
fun EditorScreen(
    vm: EditorViewModel,
    onPickImage: () -> Unit
) {
    val state by vm.preview.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when {
            state.error != null -> ErrorState(state.error!!, onPickImage)
            state.bitmap == null -> EmptyState(onPickImage)
            else -> EditorContent(vm = vm, state = state)
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
}

@Composable
private fun EditorContent(
    vm: EditorViewModel,
    state: com.alex.photoeditor.core.PreviewState
) {
    Column(modifier = Modifier.fillMaxSize()) {

        TopBar(vm = vm, state = state)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Surface1),
            contentAlignment = Alignment.Center
        ) {
            // Press-and-hold compares against the untouched original.
            val isCropMode = vm.activeTool == EditorTool.CROP
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
            modifier = Modifier.fillMaxWidth()
        )

        when (vm.activeTool) {
            EditorTool.ADJUST -> AdjustPanel(vm)
            EditorTool.FILTERS -> FilterPanel(vm)
            EditorTool.CROP -> TransformPanel(vm, cropMode = true)
            EditorTool.TRANSFORM -> TransformPanel(vm, cropMode = false)
            EditorTool.INFO -> InfoPanel(state.info)
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
            onClick = { vm.resetAll() },
            modifier = Modifier.semantics { contentDescription = "Revert all edits" }
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Revert", tint = MaterialTheme.colorScheme.onSurface)
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

        // Hold to see the original.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    if (comparing) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .pressAndHold(
                    onDown = { vm.toggleComparing(true) },
                    onUp = { vm.toggleComparing(false) }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Compare,
                contentDescription = "Hold to compare with original",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }

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
private fun EmptyState(onPickImage: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Tune,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("Offline Photo Editor", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Choose a photo to start editing. Everything stays on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        androidx.compose.material3.Button(onClick = onPickImage) {
            Text("Open photo")
        }
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
