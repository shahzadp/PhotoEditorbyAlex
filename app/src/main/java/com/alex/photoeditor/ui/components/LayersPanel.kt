package com.alex.photoeditor.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alex.photoeditor.core.ImageLayer
import com.alex.photoeditor.ui.EditorViewModel
import kotlin.math.roundToInt

/**
 * "Add image" tool.
 *
 * Up to [ImageLayer.MAX_LAYERS] images can be stacked over the base photo.
 * Each layer is dragged and pinched on the photo itself; the panel carries the
 * layer list and the opacity control.
 *
 * [layers] is passed in rather than read from the ViewModel so the composable
 * is recomposed whenever the stack changes.
 *
 * The list is vertical and top-of-list means front-most, mirroring the
 * stacking metaphor every layer editor uses. It was a horizontal row, which
 * stopped being readable past about four layers: ten side-by-side chips on a
 * narrow phone are scroller-only and give no height to show a thumbnail.
 */
@Composable
fun LayersPanel(
    vm: EditorViewModel,
    layers: List<ImageLayer>,
    onPickLayer: () -> Unit
) {
    val active = layers.firstOrNull { it.id == vm.activeLayerId } ?: layers.lastOrNull()

    // Front-most first, so the top of the list is what sits on top of the
    // photo. The index shown on each row is its real stack position.
    val ordered = remember(layers) { layers.asReversed() }

    val listState = rememberLazyListState()

    // Keep the selected layer in view when selection moves from the photo
    // itself or from an undo, without stealing position during a scroll.
    LaunchedEffect(active?.id) {
        val target = ordered.indexOfFirst { it.id == active?.id }
        if (target >= 0) listState.animateScrollToItem(target)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AddLayerButton(
                enabled = layers.size < ImageLayer.MAX_LAYERS,
                onClick = onPickLayer
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${layers.size} of ${ImageLayer.MAX_LAYERS}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (layers.isEmpty()) {
            Text(
                text = "Stack up to ${ImageLayer.MAX_LAYERS} images over this one. " +
                    "Drag to move, pinch to resize and rotate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            )
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = LIST_HEIGHT),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(ordered, key = { _, layer -> layer.id }) { index, layer ->
                val stackIndex = layers.indexOfFirst { it.id == layer.id }
                LayerRow(
                    layer = layer,
                    label = ImageLayer.labelFor(stackIndex.coerceAtLeast(0)),
                    isFront = stackIndex == layers.lastIndex,
                    isBack = stackIndex == 0,
                    selected = layer.id == active?.id,
                    onClick = { vm.selectLayer(layer.id) },
                    onToggleVisible = { vm.setLayerVisible(layer.id, !layer.visible) },
                    onRemove = { vm.removeLayer(layer.id) }
                )
            }
        }

        if (active != null) {
            Spacer(Modifier.height(4.dp))

            // Position, size and rotation are all gesture-driven on the photo
            // itself (one finger to move, two to pinch), so the only slider
            // left here is opacity.
            LabelledSliderRow(
                label = "Opacity",
                value = "${(active.opacity * 100f).roundToInt()}%",
                slider = {
                    Slider(
                        value = active.opacity,
                        onValueChange = { vm.setLayerOpacity(active.id, it) },
                        valueRange = 0f..1f
                    )
                }
            )

            // --- Ordering and reset
            ExpressiveButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                ExpressiveOutlinedButton(
                    text = "To front",
                    onClick = { vm.bringLayerToFront(active.id) },
                    modifier = Modifier.weight(1f)
                )
                ExpressiveOutlinedButton(
                    text = "To back",
                    onClick = { vm.sendLayerToBack(active.id) },
                    modifier = Modifier.weight(1f)
                )
                ExpressiveOutlinedButton(
                    text = "Reset",
                    onClick = { vm.resetLayer(active.id) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun AddLayerButton(enabled: Boolean, onClick: () -> Unit) {
    ExpressiveTonalButton(
        text = "Add image",
        icon = Icons.Filled.Add,
        enabled = enabled,
        onClick = onClick
    )
}

/**
 * One layer in the stack.
 *
 * Carries a real thumbnail of the layer image rather than a generic icon,
 * because with ten layers stacked the only way to tell them apart at a glance
 * is by what is in them. The bitmap comes from the shared [LayerStore] cache,
 * so the thumbnail reuses the very bitmap the renderer is already drawing —
 * no extra decode, no extra memory.
 */
@Composable
private fun LayerRow(
    layer: ImageLayer,
    label: String,
    isFront: Boolean,
    isBack: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleVisible: () -> Unit,
    onRemove: () -> Unit
) {
    val background by animateColorAsState(
        if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "layerRowBackground"
    )
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = label }
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            contentAlignment = Alignment.Center
        ) {
            val thumb = remember(layer.id, layer.sourcePath) {
                ImageLayer.decodeThumbnail(layer.sourcePath)
            }
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = label.takeLast(1),
                    style = MaterialTheme.typography.labelMedium,
                    color = content
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // A second line is wasted space on a ten-item list, so the stack
            // position is shown as a compact suffix instead.
            Text(
                text = "${(layer.opacity * 100f).roundToInt()}% opacity",
                style = MaterialTheme.typography.labelSmall,
                color = content,
                maxLines = 1
            )
        }

        ExpressiveIconToggle(
            icon = if (layer.visible) {
                Icons.Filled.Visibility
            } else {
                Icons.Filled.VisibilityOff
            },
            // Named specifically so a screen reader says "Hide Layer 3"
            // rather than an unlabelled "Hide".
            contentDescription = if (layer.visible) "Hide $label" else "Show $label",
            onClick = onToggleVisible
        )
        ExpressiveIconToggle(
            icon = Icons.Filled.Delete,
            contentDescription = "Remove $label",
            onClick = onRemove
        )
    }
}

@Composable
private fun IconToggle(
    visible: Boolean,
    content: androidx.compose.ui.graphics.Color,
    description: String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector = if (visible) {
        Icons.Filled.Visibility
    } else {
        Icons.Filled.VisibilityOff
    }
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun LabelledSliderRow(
    label: String,
    value: String,
    slider: @Composable () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        slider()
    }
}

@Composable
private fun SmallButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (enabled) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                MaterialTheme.colorScheme.surfaceContainer
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { contentDescription = text }
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}