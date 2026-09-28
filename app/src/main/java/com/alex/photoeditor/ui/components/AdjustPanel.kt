package com.alex.photoeditor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alex.photoeditor.core.AdjustmentKind
import com.alex.photoeditor.ui.EditorViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

/** Adjustment tool: a horizontal picker plus one contextual slider. */
@Composable
fun AdjustPanel(vm: EditorViewModel) {
    val state = vm.currentAdjustments()
    val active = vm.activeAdjustment

    // Local copy so dragging stays smooth; pushed to the repo on release.
    var sliderValue by remember(active) { mutableFloatStateOf(active.valueIn(state)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(bottom = 12.dp)
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(AdjustmentKind.entries.toList()) { kind ->
                val selected = kind == active
                val isModified = kind.valueIn(state) != 0f
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable {
                            vm.selectAdjustment(kind)
                            sliderValue = kind.valueIn(state)
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .semantics { contentDescription = kind.label }
                ) {
                    Text(
                        text = if (isModified) "${kind.label} •" else kind.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // Value readout, plus a quick reset for the active control.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                active.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatValue(active, sliderValue),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable {
                        sliderValue = 0f
                        vm.selectAdjustment(active)
                        vm.onSliderChange(active, 0f)
                        vm.onSliderRelease()
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .semantics { contentDescription = "Reset ${active.label}" }
            ) {
                Text(
                    "Reset",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Slider(
            value = sliderValue,
            onValueChange = {
                sliderValue = it
                vm.onSliderChange(active, it)
            },
            onValueChangeFinished = vm::onSliderRelease,
            valueRange = active.min..active.max,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .semantics { contentDescription = "${active.label} amount" }
        )
    }
}

/** Formats a control value for display, scaled per control type. */
private fun formatValue(kind: AdjustmentKind, v: Float): String =
    when (kind) {
        AdjustmentKind.EXPOSURE -> String.format("%+.2f EV", v)
        else -> {
            val pct = (v * 100).roundToInt()
            if (pct > 0) "+$pct" else "$pct"
        }
    }
