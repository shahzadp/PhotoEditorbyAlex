package com.alex.photoeditor.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Filter
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.alex.photoeditor.ui.EditorTool
import com.alex.photoeditor.ui.theme.LocalMotion

/**
 * Horizontal tool selector at the bottom of the editor.
 *
 * Scrollable because there are eight tools, which no longer fit across a
 * narrow phone: with `SpaceEvenly` the rightmost entries were pushed off screen
 * and Info was unreachable.
 *
 * The selected tool is drawn as a filled pill whose icon and label animate to
 * the primary colour, and the pill's width springs open. That transition is
 * the main thing that makes the rail feel responsive rather than switching
 * abruptly between two static states.
 *
 * Only the *selected* chip animates. The other seven are static draws with
 * identical parameters, so Compose reuses their nodes and the rail costs no
 * more per frame than the un-animated version did.
 */
@Composable
fun ToolRail(
    active: EditorTool,
    onSelect: (EditorTool) -> Unit,
    collapsed: Boolean = false,
    onToggleCollapsed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // When collapsed, the rail itself shrinks to a single small toggle and
        // the selected tool is named beside it. The point is to hand the
        // vertical space to the preview, so keeping eight full chips on screen
        // would defeat it - a rail that stays visible is not a collapsed rail.
        if (collapsed) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Small arrow-only button, as asked for.
                IconButton(
                    onClick = onToggleCollapsed,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .semantics {
                            contentDescription = "Expand tools"
                        }
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = active.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .size(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EditorTool.entries.forEach { tool ->
                ToolChip(tool, iconFor(tool), active, onSelect)
            }
            Spacer(Modifier.width(4.dp))
            // Small arrow-only button, matching the collapsed one.
            IconButton(
                onClick = onToggleCollapsed,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .semantics {
                        contentDescription = "Collapse tools"
                    }
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // A hairline divider so the panel below reads as its own surface
        // without a heavy border.
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .size(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

/** Icon for each tool, kept in one place so the order stays readable. */
private fun iconFor(tool: EditorTool) = when (tool) {
    EditorTool.ADJUST -> Icons.Filled.Tune
    EditorTool.FILTERS -> Icons.Filled.Filter
    EditorTool.CROP -> Icons.Filled.Crop
    EditorTool.TRANSFORM -> Icons.Filled.TurnSlightRight
    EditorTool.TAP_SELECT -> Icons.Filled.ContentCut
    EditorTool.LAYERS -> Icons.Filled.AddPhotoAlternate
    EditorTool.TEXT -> Icons.Filled.TextFields
    EditorTool.DRAW -> Icons.Filled.Brush
    EditorTool.INFO -> Icons.Filled.Info
}

@Composable
private fun ToolChip(
    tool: EditorTool,
    icon: ImageVector,
    active: EditorTool,
    onSelect: (EditorTool) -> Unit
) {
    val isActive = tool == active

    // Spring from the target so the pill visibly opens and closes rather than
    // snapping. Colour follows the same curve.
    val width by animateDpAsState(
        targetValue = if (isActive) 72.dp else 56.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "toolChipWidth"
    )
    val background by animateColorAsState(
        targetValue = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "toolChipBackground"
    )
    val content by animateColorAsState(
        targetValue = if (isActive) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "toolChipContent"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(width)
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable { onSelect(tool) }
            .padding(vertical = 8.dp)
            .semantics {
                contentDescription = tool.label
                this.selected = isActive
            }
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(3.dp))
        Text(
            text = tool.label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1
        )
    }
}
