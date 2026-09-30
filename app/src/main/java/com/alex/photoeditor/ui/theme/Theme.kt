package com.alex.photoeditor.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Dark editor palette drawn from the app icon colours. Used as the fallback
// when the system has no dynamic colour to offer.
val PrismaPink = Color(0xFFEF476F)
val PrismaYellow = Color(0xFFFFD166)
val PrismaGreen = Color(0xFF06D6A0)
val PrismaBlue = Color(0xFF118AB2)

val Surface0 = Color(0xFF101014)
val Surface1 = Color(0xFF1A1A21)
val Surface2 = Color(0xFF25252E)
val OutlineDim = Color(0xFF3A3A46)

private val BrandDarkColors = darkColorScheme(
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
 * The light counterpart of [BrandDarkColors].
 *
 * Needed because dynamic colour follows the *system* light/dark setting. A user
 * on a light-themed phone with an wallpaper-derived palette would otherwise get
 * a light accent colour painted onto the app's hard-dark surfaces, which fails
 * contrast in one direction or the other.
 */
private val BrandLightColors = lightColorScheme(
    primary = Color(0xFFB3264E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E2),
    onPrimaryContainer = Color(0xFF3E001D),
    secondary = Color(0xFF006B54),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB8F5E2),
    onSecondaryContainer = Color(0xFF002019),
    tertiary = Color(0xFF7A5900),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEFA8),
    onTertiaryContainer = Color(0xFF261A00),
    background = Color(0xFFFCF8FA),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFCF8FA),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE6E1E5),
    onSurfaceVariant = Color(0xFF48454A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F1F4),
    surfaceContainer = Color(0xFFF1EBEE),
    surfaceContainerHigh = Color(0xFFEBE5E9),
    surfaceContainerHighest = Color(0xFFE5DFE4),
    outline = Color(0xFF79767B),
    outlineVariant = Color(0xFFCAC4CA),
    error = Color(0xFFB3261E),
    onError = Color.White
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

/**
 * True when the active scheme is a light one.
 *
 * A few surfaces — most importantly the photo preview stage — need to stay dark
 * regardless of theme, because judging tone and colour against a light or
 * wallpaper-tinted surround biases perception. That is unacceptable in a photo
 * tool, so the *chrome* follows the system while the *canvas* does not.
 */
val LocalIsDarkTheme = staticCompositionLocalOf { true }

/** True when the palette came from the system rather than the app's own. */
val LocalUsesDynamicColor = staticCompositionLocalOf { false }

/**
 * Builds the colour scheme for the current system settings.
 *
 * Dynamic colour (Material You) is used wherever the platform provides it,
 * which is API 31+. Below that there is no system palette to read, so the
 * brand scheme is used instead.
 */
@Composable
private fun resolveColorScheme(
    darkTheme: Boolean,
    useDynamicColor: Boolean
): ColorScheme {
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    if (useDynamicColor && supportsDynamic) {
        val context = LocalContext.current
        return if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    return if (darkTheme) BrandDarkColors else BrandLightColors
}

@Composable
fun PhotoEditorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Follow the wallpaper-derived system palette.
     *
     * Defaults to true, which is what makes the app match whatever colour the
     * user has chosen on their phone.
     */
    useDynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = resolveColorScheme(darkTheme, useDynamicColor)

    CompositionLocalProvider(
        LocalMotion provides MotionSpec(emphasized = 400, standard = 200),
        LocalIsDarkTheme provides (colors == BrandDarkColors || isLikelyDark(colors)),
        LocalUsesDynamicColor provides (useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = EditorTypography,
            shapes = EditorShapes,
            content = content
        )
    }
}

/**
 * Best-effort dark/light test for a resolved scheme.
 *
 * Relative luminance is the honest way to ask this rather than comparing
 * against a fixed colour, because a dynamic scheme is derived from an
 * arbitrary wallpaper and could be either.
 */
private fun isLikelyDark(colors: ColorScheme): Boolean {
    // Color's red/green/blue are packed Float components in [0,1], not the
    // 0..255 ints the sRGB transfer function expects.
    fun linearise(v: Float): Double {
        val s = v.toDouble()
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
    val luminance = 0.2126 * linearise(colors.surface.red) +
        0.7152 * linearise(colors.surface.green) +
        0.0722 * linearise(colors.surface.blue)
    return luminance < 0.5
}

