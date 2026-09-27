package com.example.workshifttracker

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val ShiftWatchLight = lightColorScheme(
    primary = Color(0xFF6854D9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E2FF),
    onPrimaryContainer = Color(0xFF21124F),
    secondary = Color(0xFF3F6B66),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6ECE8),
    onSecondaryContainer = Color(0xFF102E2B),
    tertiary = Color(0xFF9B5B22),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCC3),
    onTertiaryContainer = Color(0xFF341300),
    background = Color(0xFFF9F7FD),
    onBackground = Color(0xFF1D1A23),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF1D1A23),
    surfaceVariant = Color(0xFFE9E5EF),
    onSurfaceVariant = Color(0xFF4A4650),
    outline = Color(0xFF7B7682),
    outlineVariant = Color(0xFFCCC6D2),
    error = Color(0xFFB3261E)
)

private val ShiftWatchDark = darkColorScheme(
    primary = Color(0xFFC9BEFF),
    onPrimary = Color(0xFF342079),
    primaryContainer = Color(0xFF4C379E),
    onPrimaryContainer = Color(0xFFE8E1FF),
    secondary = Color(0xFFB5CCC7),
    onSecondary = Color(0xFF203733),
    secondaryContainer = Color(0xFF354E4A),
    onSecondaryContainer = Color(0xFFD2E8E3),
    tertiary = Color(0xFFFFB77C),
    onTertiary = Color(0xFF542800),
    tertiaryContainer = Color(0xFF753B08),
    onTertiaryContainer = Color(0xFFFFDCC3),
    background = Color(0xFF0F0D13),
    onBackground = Color(0xFFEAE4ED),
    surface = Color(0xFF151218),
    onSurface = Color(0xFFEAE4ED),
    surfaceVariant = Color(0xFF46414A),
    onSurfaceVariant = Color(0xFFCBC4CF),
    outline = Color(0xFF958E99),
    outlineVariant = Color(0xFF4A454F),
    error = Color(0xFFFFB4AB)
)

private val ShiftWatchTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5f).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.25f).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 27.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp
    )
)

@Composable
fun ShiftWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) ShiftWatchDark else ShiftWatchLight,
        typography = ShiftWatchTypography,
        content = content
    )
}
