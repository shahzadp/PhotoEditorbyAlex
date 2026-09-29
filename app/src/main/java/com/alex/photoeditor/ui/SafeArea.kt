package com.alex.photoeditor.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Padding that keeps UI clear of the camera notch and the navigation bar.
 *
 * `MainActivity` calls `enableEdgeToEdge()`, so the window deliberately extends
 * behind the system bars. Without consuming the insets the top bar sat under
 * the notch, and the tool rail plus the panel sliders were hidden behind the
 * three-button navigation bar.
 *
 * [WindowInsets.safeDrawing] is the union of the status bar, the display
 * cutout, the navigation bar and the IME, which is exactly the region that
 * must not hold content.
 */
@Immutable
data class SafeArea(
    val top: Dp,
    val bottom: Dp,
    val start: Dp,
    val end: Dp
) {
    companion object {
        val Zero = SafeArea(0.dp, 0.dp, 0.dp, 0.dp)
    }
}

/**
 * Reads the current safe-area insets.
 *
 * On the test device (1080x2436, density 3.0) this reports a 38dp top inset
 * from the notch and a 40dp bottom inset from the three-button bar.
 */
@Composable
fun rememberSafeArea(): SafeArea {
    val density = LocalDensity.current
    val padding = WindowInsets.safeDrawing.asPaddingValues()

    return with(density) {
        SafeArea(
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding(),
            start = padding.calculateStartPadding(LocalLayoutDirection.current),
            end = padding.calculateEndPadding(LocalLayoutDirection.current)
        )
    }
}
