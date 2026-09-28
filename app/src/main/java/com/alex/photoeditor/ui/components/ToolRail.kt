package com.alex.photoeditor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Filter
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.alex.photoeditor.ui.EditorTool

/** Horizontal tool selector at the bottom of the editor. */
@Composable
fun ToolRail(
    active: EditorTool,
    onSelect: (EditorTool) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolChip(EditorTool.ADJUST, Icons.Filled.Tune, active, onSelect)
        ToolChip(EditorTool.FILTERS, Icons.Filled.Filter, active, onSelect)
        ToolChip(EditorTool.CROP, Icons.Filled.Crop, active, onSelect)
        ToolChip(EditorTool.TRANSFORM, Icons.Filled.TurnSlightRight, active, onSelect)
        ToolChip(EditorTool.INFO, Icons.Filled.Info, active, onSelect)
    }
}

@Composable
private fun ToolChip(
    tool: EditorTool,
    icon: ImageVector,
    active: EditorTool,
    onSelect: (EditorTool) -> Unit
) {
    val isActive = tool == active
    val tint = if (isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    androidx.compose.foundation.layout.Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onSelect(tool) }
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .semantics {
                contentDescription = tool.label
                this.selected = isActive
            }
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(2.dp))
        Text(
            tool.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint
        )
    }
}
