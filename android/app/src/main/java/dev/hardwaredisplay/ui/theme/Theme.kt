package dev.hardwaredisplay.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
    val Background = Color(0xFF101217)
    val Card = Color(0xFF1A1E25)
    val Raised = Color(0xFF222831)
    val Border = Color(0xFF353E49)
    val Text = Color(0xFFF0F4F6)
    val TextSecondary = Color(0xFFAFBBC5)
    val Accent = Color(0xFFD4F86E)
    val Series1 = Color(0xFF71D6E7)
    val Series2 = Color(0xFFC2A7FF)
    val Warning = Color(0xFFFFCF79)
    val Danger = Color(0xFFFF8C8C)
    val Inactive = Color(0xFF6B7784)
}

val NumberFont = FontFamily.Monospace

private val UiFont = FontFamily.SansSerif

private val scheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.Background,
    secondary = Palette.Series1,
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Card,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Raised,
    onSurfaceVariant = Palette.TextSecondary,
    outline = Palette.Border,
    error = Palette.Danger,
)

private val typography = Typography(
    displayLarge = TextStyle(
        fontFamily = NumberFont,
        fontWeight = FontWeight.Medium,
        fontSize = 56.sp,
        lineHeight = 60.sp,
        letterSpacing = (-1).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = NumberFont,
        fontWeight = FontWeight.Medium,
        fontSize = 27.sp,
        lineHeight = 32.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = UiFont,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = UiFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = UiFont,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = UiFont,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = UiFont,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun HardwareDisplayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        content = content,
    )
}

object Dimens {
    val ScreenPadding = 16.dp
    val CardRadius = 24.dp
    val CompactRadius = 16.dp
    val Gap = 12.dp
    val MinTouch = 48.dp
}
