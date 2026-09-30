package com.alex.photoeditor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alex.photoeditor.core.ColourUtils
import com.alex.photoeditor.core.TextFonts
import com.alex.photoeditor.core.TextLayer
import com.alex.photoeditor.ui.EditorViewModel
import kotlin.math.roundToInt

/**
 * "Text" tool.
 *
 * Up to [TextLayer.MAX_TEXT_LAYERS] text objects, independent of the image
 * layer stack, so twenty overlaid objects can coexist.
 *
 * The panel is a bounded scrolling column rather than a growing one: a text
 * field, fonts, colour and a list of objects cannot all fit in the space below
 * the photo, and letting the panel grow pushes the preview off screen.
 */
@Composable
fun TextPanel(
    vm: EditorViewModel,
    texts: List<TextLayer>,
    eyedropperOn: Boolean,
    onEyedropperChange: (Boolean) -> Unit,
    onAddText: () -> Unit
) {
    val active = texts.firstOrNull { it.id == vm.activeTextId } ?: texts.lastOrNull()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(bottom = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ExpressiveTonalButton(
                text = "Add text",
                icon = Icons.Filled.Add,
                enabled = texts.size < TextLayer.MAX_TEXT_LAYERS,
                onClick = onAddText
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${texts.size} of ${TextLayer.MAX_TEXT_LAYERS}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (texts.isEmpty()) {
            Text(
                text = "Add up to ${TextLayer.MAX_TEXT_LAYERS} text objects. " +
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

        // --- Object list, placed exactly where the image layer list sits:
        // directly under the header.
        //
        // It used to come last, after the field, fonts, styles, colour wheel
        // and opacity. With a tall panel above it, the list was the element
        // that got squeezed, which is what made it read as "smaller" than the
        // Add image list even though both had the same 190dp cap. Same order
        // in both panels means the two are directly comparable and neither
        // list competes with the controls for room.
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = LIST_HEIGHT),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(texts.asReversed(), key = { _, t -> t.id }) { _, layer ->
                val index = texts.indexOfFirst { it.id == layer.id }
                TextRow(
                    layer = layer,
                    label = TextLayer.labelFor(index.coerceAtLeast(0)),
                    selected = layer.id == active?.id,
                    onClick = { vm.selectText(layer.id) },
                    onToggleVisible = { vm.setTextVisible(layer.id, !layer.visible) },
                    onRemove = { vm.removeText(layer.id) }
                )
            }
        }

        // --- Content field for the selected object
        if (active != null) {
            // The field keeps its own copy of the string.
            //
            // Driving it straight from `active.text` meant every keystroke took
            // a full round trip through the repository and a re-render before
            // the value came back, and a controlled field that receives its
            // value again resets the selection — so the caret jumped to the
            // end of the word instead of following the finger. Local state
            // keeps the caret where the user put it; the repository is still
            // updated on every change so nothing is lost if the app dies.
            var content by remember(active.id) { mutableStateOf(active.text) }
            BasicTextField(
                value = content,
                onValueChange = { entered ->
                    content = entered
                    vm.setTextContent(active.id, entered)
                },
                singleLine = true,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            )

            Spacer(Modifier.height(8.dp))

            // --- Fonts. Each option is previewed in its own face, so the
            // choice is made by looking rather than by reading a label.
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(TextFonts.options) { option ->
                    val selected = option.key == active.fontKey
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                }
                            )
                            .clickable { vm.setTextFont(active.id, option.key) }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "Aa",
                            style = MaterialTheme.typography.labelLarge.copy(
                                // A platform Typeface rather than FontFamily,
                                // which takes a Typeface and not a family name.
                                fontFamily = androidx.compose.ui.text.font.FontFamily(
                                    android.graphics.Typeface.create(
                                        option.family,
                                        android.graphics.Typeface.NORMAL
                                    )
                                )
                            ),
                            color = if (selected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // --- Style and colour
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ExpressiveIconToggle(
                    icon = Icons.Filled.FormatBold,
                    contentDescription = "Bold",
                    selected = active.bold,
                    onClick = { vm.setTextBold(active.id, !active.bold) }
                )
                ExpressiveIconToggle(
                    icon = Icons.Filled.FormatItalic,
                    contentDescription = "Italic",
                    selected = active.italic,
                    onClick = { vm.setTextItalic(active.id, !active.italic) }
                )
                ExpressiveIconToggle(
                    icon = Icons.Filled.Colorize,
                    contentDescription = "Pick a colour from the photo",
                    selected = eyedropperOn,
                    onClick = { onEyedropperChange(!eyedropperOn) }
                )
                HexField(
                    color = active.color,
                    onColorChange = { vm.setTextColor(active.id, it) }
                )
            }

            Spacer(Modifier.height(4.dp))

            // --- Opacity
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Opacity",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = active.opacity,
                    onValueChange = { vm.setTextOpacity(active.id, it) },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor =
                            MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                )
                Text(
                    "${(active.opacity * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // --- Colour wheel, collapsed by default.
            //
            // The wheel is 160dp and the swatches cover the common cases, so
            // showing it permanently would push the object list off screen.
            var showPicker by remember(active.id) { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
            ) {
                ExpressiveOutlinedButton(
                    text = if (showPicker) "Hide colours" else "Colour wheel",
                    onClick = { showPicker = !showPicker },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(8.dp))
                ExpressiveOutlinedButton(
                    text = "Reset",
                    onClick = { vm.resetText(active.id) },
                    modifier = Modifier.weight(1f)
                )
            }

            if (showPicker) {
                ColourPicker(
                    color = active.color,
                    onColorChange = { vm.setTextColor(active.id, it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            if (eyedropperOn) {
                Text(
                    text = "Tap anywhere on the photo to pick that colour",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun TextRow(
    layer: TextLayer,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleVisible: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            )
            .clickable(onClick = onClick)
            // Same minimum as an image layer row, so the two lists are
            // pixel-identical in height rather than merely similarly capped.
            .heightIn(min = ROW_HEIGHT)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // A colour dot, so the text's colour is identifiable in the list
        // without opening it. Sized to match the layer row's thumbnail so the
        // two lists have the same optical weight.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ColourUtils.fromArgbLong(layer.color))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(10.dp)
                )
        )
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = layer.text.ifBlank { label },
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$label • ${(layer.opacity * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1
            )
        }
        ExpressiveIconToggle(
            icon = if (layer.visible) {
                Icons.Filled.Visibility
            } else {
                Icons.Filled.VisibilityOff
            },
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
