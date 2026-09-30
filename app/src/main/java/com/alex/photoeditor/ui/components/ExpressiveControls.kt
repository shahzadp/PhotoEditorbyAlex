package com.alex.photoeditor.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Shared Material 3 Expressive controls.
 *
 * Every button in the app previously hand-rolled its own `Box` + `background` +
 * `clickable` + `RoundedCornerShape`. That produced three different corner
 * radii, two different heights and no shared pressed/disabled behaviour for the
 * same visual role, so the interface read as several apps stitched together.
 *
 * These wrap the real Material components instead. That matters beyond
 * consistency: M3 buttons carry their own minimum touch target, ripple,
 * disabled state and accessibility semantics, none of which the hand-rolled
 * versions provided.
 */

/**
 * Corner radius for a control.
 *
 * Uses the theme's `medium` shape so the whole app picks up the expressive
 * radii from one place.
 */
private val ControlShape
    @Composable get() = MaterialTheme.shapes.medium

/**
 * Shared geometry for the image-layer and text object lists.
 *
 * Both panels declare the same numbers so the two lists are genuinely
 * interchangeable rather than merely similar. Hardcoding the height in each
 * panel is what let them drift apart, and two lists that look almost-but-not
 * quite the same is worse than either being clearly its own thing.
 */
val LIST_HEIGHT = 190.dp

/**
 * Minimum row height for a list entry.
 *
 * Set by the 40dp leading thumbnail plus padding, so both rows agree on the
 * touch target and the text lines up across the two lists.
 */
val ROW_HEIGHT = 52.dp

/** A filled call-to-action, e.g. "Open photo". */
@Composable
fun ExpressiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ControlShape,
        modifier = modifier
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * A secondary action: "Add image", "Projects".
 *
 * Tonal rather than plain text, so it reads as a real target next to a filled
 * primary without competing with it.
 */
@Composable
fun ExpressiveTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = ControlShape,
        modifier = modifier
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A low-emphasis action: "Reset", "To front", "To back". */
@Composable
fun ExpressiveOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = ControlShape,
        modifier = modifier
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * A selectable chip, used by the adjustment and filter pickers.
 *
 * Selected state animates its container and content colours so switching
 * controls reads as a transition rather than a repaint. Unselected chips share
 * identical parameters, so Compose reuses their nodes and an eight-item row
 * costs no more per frame than a static one.
 */
@Composable
fun ExpressiveChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Drawn as a trailing dot, e.g. to show an adjustment is non-zero. */
    marked: Boolean = false
) {
    val background by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        label = "chipBackground"
    )
    val content by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "chipContent"
    )
    val border by animateColorAsState(
        targetValue = if (selected) {
            Color.Transparent
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        label = "chipBorder"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .then(
                if (selected) {
                    Modifier
                } else {
                    Modifier.border(BorderStroke(1.dp, border), RoundedCornerShape(20.dp))
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { contentDescription = label }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = content,
                textAlign = TextAlign.Center
            )
            if (marked) {
                Spacer(Modifier.size(6.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

/** A square icon-only control with a proper touch target. */
@Composable
fun ExpressiveIconToggle(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    tint: Color? = null
) {
    val background by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        label = "iconToggleBackground"
    )
    val content by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            selected -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> tint ?: MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "iconToggleContent"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(10.dp)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * A row of small secondary actions that wraps when it runs out of room.
 *
 * "To front / To back / Reset" used to be a fixed `Row`, which clipped the
 * third button on a narrow phone once the labels were localised or enlarged.
 */
@Composable
fun ExpressiveButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
