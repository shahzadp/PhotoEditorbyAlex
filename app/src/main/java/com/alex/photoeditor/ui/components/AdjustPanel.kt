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
                ExpressiveChip(
                    label = kind.label,
                    selected = selected,
                    // A trailing dot marks a control that has been moved off
                    // its default, so the user can see at a glance what is
                    // non-zero without reading every value.
                    marked = isModified,
                    onClick = {
                        vm.selectAdjustment(kind)
                        sliderValue = kind.valueIn(state)
                    }
                )
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
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End
            )
            Spacer(Modifier.width(8.dp))
            ExpressiveOutlinedButton(
                text = "Reset",
                enabled = sliderValue != 0f,
                onClick = {
                    sliderValue = 0f
                    vm.selectAdjustment(active)
                    vm.onSliderChange(active, 0f)
                    vm.onSliderRelease()
                }
            )
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
                // Expressive sliders run a thicker active track with a gap
                // before the thumb, the M3 expressive signature.
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
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
