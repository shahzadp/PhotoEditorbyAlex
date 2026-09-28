package com.alex.photoeditor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
    secondary = PrismaGreen,
    onSecondary = Color(0xFF00281D),
    tertiary = PrismaYellow,
    background = Surface0,
    onBackground = Color(0xFFECECEC),
    surface = Surface1,
    onSurface = Color(0xFFECECEC),
    surfaceVariant = Surface2,
    onSurfaceVariant = Color(0xFFB4B4C0),
    outline = OutlineDim
)

private val EditorTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun PhotoEditorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // The editor is intentionally always dark: judging colour on a light UI
    // biases perceived tone, which is unacceptable for a photo tool.
    MaterialTheme(
        colorScheme = EditorColors,
        typography = EditorTypography,
        content = content
    )
}
