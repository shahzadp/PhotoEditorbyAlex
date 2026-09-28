package com.alex.photoeditor.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alex.photoeditor.core.CropRect
import kotlin.math.roundToInt
/**
 * Preview plus a crop overlay that is guaranteed to line up with the image.
 *
 * The preview uses `ContentScale.Fit`, so it is letterboxed inside its bounds.
 * The overlay has to cover exactly the *rendered image* rectangle, otherwise
 * dragging a corner would be offset. This composable measures the box, derives
 * the fitted image size from the bitmap's aspect ratio, and stacks the overlay
 * on top at exactly that size.
 */
@Composable
fun CropStage(
    bitmap: android.graphics.Bitmap,
    initial: CropSelection,
    padding: Dp = 12.dp,
    onPreviewChange: (CropSelection) -> Unit,
    onCommit: (CropSelection) -> Unit,
    modifier: Modifier = Modifier
) {
    // Local selection so dragging is smooth; committed on release.
    var selection by remember { mutableStateOf(initial) }

    // `initial` is authoritative whenever it changes from outside (preset
    // applied, reset pressed, undo, tool re-entered). It no longer echoes each
    // live drag frame, so syncing unconditionally cannot fight the gesture.
    val initialKey = "${initial.left},${initial.top},${initial.right},${initial.bottom}"
    LaunchedEffect(initialKey) {
        selection = initial
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val maxW = maxWidth - padding * 2
        val maxH = maxHeight - padding * 2

        // Fit the bitmap inside the available space, preserving aspect ratio.
        val imgW: Dp
        val imgH: Dp
        if (maxW.value > 0f && maxH.value > 0f) {
            val ar = bitmap.width.toFloat() / bitmap.height.toFloat()
            val availRatio = maxW / maxH
            imgW = if (ar > availRatio) maxW else maxH * ar
            imgH = if (ar > availRatio) maxW / ar else maxH
        } else {
            imgW = maxW
            imgH = maxH
        }

        // Re-sync happens above via LaunchedEffect(initialKey).

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Constrain to the exact fitted-image size so the crop frame
            // lines up with the photo edges rather than the panel edges.
            Box(
                modifier = Modifier
                    .width(imgW)
                    .height(imgH),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Photo being cropped",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )

                CropOverlay(
                    selection = selection,
                    onChange = {
                        selection = it
                        onPreviewChange(it)
                    },
                    onCommit = onCommit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Converts a fractional selection into a pixel [CropRect] for the given image.
 *
 * Clamps defensively: a malformed selection must never produce out-of-range
 * or inverted pixel coordinates, which would corrupt the stored crop.
 */
fun CropSelection.toCropRect(imageWidth: Int, imageHeight: Int): CropRect {
    if (imageWidth <= 0 || imageHeight <= 0) {
        return CropRect(0, 0, 1, 1)
    }

    val safe = clamped()

    val l = (safe.left * imageWidth).toInt().coerceIn(0, imageWidth - 1)
    val t = (safe.top * imageHeight).toInt().coerceIn(0, imageHeight - 1)
    val r = (safe.right * imageWidth).roundToInt().coerceIn(l + 1, imageWidth)
    val b = (safe.bottom * imageHeight).roundToInt().coerceIn(t + 1, imageHeight)
    return CropRect(l, t, r, b)
}

/** Converts a pixel [CropRect] back into a fractional selection. */
fun CropRect.toSelection(imageWidth: Int, imageHeight: Int): CropSelection {
    if (imageWidth <= 0 || imageHeight <= 0) return CropSelection.FULL
    return CropSelection(
        left = left.toFloat() / imageWidth,
        top = top.toFloat() / imageHeight,
        right = right.toFloat() / imageWidth,
        bottom = bottom.toFloat() / imageHeight
    ).clamped()
}
