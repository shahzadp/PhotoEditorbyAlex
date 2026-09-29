package com.alex.photoeditor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Dark editor palette drawn from the app icon colours.
val PrismaPink = Color(0xFFEF476F)
val PrismaYellow = Color(0xFFFFD166)
val PrismaGreen = Color(0xFF06D6A0)
val PrismaBlue = Color(0xFF118AB2)

val Surface0 = Color(0xFF101014)
val Surface1 = Color(0xFF1A1A21)
val Surface2 = Color(0xFF25252E)
val OutlineDim = Color(0xFF3A3A46)

private val EditorColors = darkColorScheme(
    primary = PrismaPink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5C1830),
    onPrimaryContainer = Color(0xFFFFD9E2),
    secondary = PrismaGreen,
    onSecondary = Color(0xFF00281D),
    secondaryContainer = Color(0xFF0B4636),
    onSecondaryContainer = Color(0xFFB8F5E2),
    tertiary = PrismaYellow,
    onTertiary = Color(0xFF3B2F00),
    tertiaryContainer = Color(0xFF544300),
    onTertiaryContainer = Color(0xFFFFEFA8),
    background = Surface0,
    onBackground = Color(0xFFECECEC),
    surface = Surface1,
    onSurface = Color(0xFFECECEC),
    surfaceVariant = Surface2,
    onSurfaceVariant = Color(0xFFB4B4C0),
    surfaceContainerLowest = Color(0xFF0B0B0E),
    surfaceContainerLow = Color(0xFF16161C),
    surfaceContainer = Surface2,
    surfaceContainerHigh = Color(0xFF2E2E39),
    surfaceContainerHighest = Color(0xFF383844),
    outline = OutlineDim,
    outlineVariant = Color(0xFF2C2C36),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

/**
 * Rounder shapes than the Material baseline.
 *
 * Expressive leans heavily into large, soft corner radii, which is what makes
 * the controls read as tactile rather than utilitarian. These are just radii
 * values, so there is no rendering cost.
 */
private val EditorShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp)
)

private val EditorTypography = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

/**
 * Motion durations in milliseconds, exposed as a composition local.
 *
 * Material 3 Expressive is defined as much by how things move as by how they
 * look. Rather than hardcoding durations at each call site, components read
 * these so the whole app animates consistently.
 */
data class MotionSpec(val emphasized: Int, val standard: Int)

val LocalMotion = staticCompositionLocalOf { MotionSpec(emphasized = 400, standard = 200) }

@Composable
fun PhotoEditorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // The editor is intentionally always dark: judging colour on a light UI
    // biases perceived tone, which is unacceptable for a photo tool.
    CompositionLocalProvider(
        LocalMotion provides MotionSpec(emphasized = 400, standard = 200)
    ) {
        MaterialTheme(
            colorScheme = EditorColors,
            typography = EditorTypography,
            shapes = EditorShapes,
            content = content
        )
    }
}
