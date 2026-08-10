package com.iptv.tv.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF12D8FF),
    onPrimary = Color(0xFF00131B),
    primaryContainer = Color(0xFF07354A),
    onPrimaryContainer = Color(0xFFDDF8FF),
    secondary = Color(0xFF65BFFF),
    onSecondary = Color(0xFF03131F),
    secondaryContainer = Color(0xFF123A52),
    onSecondaryContainer = Color(0xFFD9F0FF),
    tertiary = Color(0xFFFFB547),
    background = Color(0xFF02070D),
    onBackground = Color(0xFFF0F8FF),
    surface = Color(0xFF06111B),
    onSurface = Color(0xFFF3FAFF),
    surfaceVariant = Color(0xFF0A2232),
    onSurfaceVariant = Color(0xFFC5D9E6),
    outline = Color(0xFF356077),
    outlineVariant = Color(0xFF17384A)
)

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF006C86),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9EAFA),
    onPrimaryContainer = Color(0xFF001F29),
    secondary = Color(0xFF17648F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE9FB),
    onSecondaryContainer = Color(0xFF082338),
    tertiary = Color(0xFFC2780A),
    background = Color(0xFFF7FAFE),
    onBackground = Color(0xFF0F1A23),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111A22),
    surfaceVariant = Color(0xFFEAF3F8),
    onSurfaceVariant = Color(0xFF334B5A),
    outline = Color(0xFF607F91),
    outlineVariant = Color(0xFFD2E0E8)
)

private val IptvTypography: Typography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 19.sp,
        lineHeight = 24.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 23.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 19.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 20.sp
    )
)

private val IptvShapes: Shapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
)

@Composable
fun IptvTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = IptvTypography,
        shapes = IptvShapes,
        content = content
    )
}
