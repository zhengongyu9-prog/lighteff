package com.qingxiao.lighteff.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6B5A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6EEE4),
    onPrimaryContainer = Color(0xFF123F35),
    secondary = Color(0xFFB87433),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFBE7D2),
    onSecondaryContainer = Color(0xFF4A2A0C),
    tertiary = Color(0xFF4C6B9A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDE6F6),
    onTertiaryContainer = Color(0xFF233A5E),
    background = Color(0xFFF6F3EC),
    onBackground = Color(0xFF1D1B17),
    surface = Color(0xFFFFFDF8),
    onSurface = Color(0xFF1D1B17),
    surfaceVariant = Color(0xFFEBE6DC),
    onSurfaceVariant = Color(0xFF5D574D),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBF8F1),
    surfaceContainer = Color(0xFFF3EFE6),
    surfaceContainerHigh = Color(0xFFEDE8DF),
    surfaceContainerHighest = Color(0xFFE6E1D7),
    outline = Color(0xFF8B8478),
    outlineVariant = Color(0xFFD8D2C7)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD3BC),
    onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF1E5243),
    onPrimaryContainer = Color(0xFFD6EEE4),
    secondary = Color(0xFFE9BE8D),
    onSecondary = Color(0xFF47290B),
    secondaryContainer = Color(0xFF5C3A1A),
    onSecondaryContainer = Color(0xFFFBE7D2),
    tertiary = Color(0xFFA9C3F0),
    onTertiary = Color(0xFF13243D),
    tertiaryContainer = Color(0xFF324A72),
    onTertiaryContainer = Color(0xFFDDE6F6),
    background = Color(0xFF111512),
    onBackground = Color(0xFFE7E3DB),
    surface = Color(0xFF171C18),
    onSurface = Color(0xFFE7E3DB),
    surfaceVariant = Color(0xFF2A302B),
    onSurfaceVariant = Color(0xFFC7C2B8),
    surfaceContainerLowest = Color(0xFF0C100D),
    surfaceContainerLow = Color(0xFF171C18),
    surfaceContainer = Color(0xFF1B201C),
    surfaceContainerHigh = Color(0xFF252B26),
    surfaceContainerHighest = Color(0xFF303730),
    outline = Color(0xFF918B80),
    outlineVariant = Color(0xFF3A403A)
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp
    )
)

@Composable
fun LightEffTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
