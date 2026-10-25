package com.tvdeck.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0E7C7B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEFEA),
    onPrimaryContainer = Color(0xFF00302E),
    secondary = Color(0xFF4F5BD5),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E2FF),
    onSecondaryContainer = Color(0xFF111A66),
    tertiary = Color(0xFFD97706),
    background = Color(0xFFF2F7F6),
    onBackground = Color(0xFF0F1D1C),
    surface = Color.White,
    onSurface = Color(0xFF0F1D1C),
    surfaceVariant = Color(0xFFE2EEEC),
    onSurfaceVariant = Color(0xFF3C5553),
    outline = Color(0xFF7C9391),
    outlineVariant = Color(0xFFD3E2DF),
    error = Color(0xFFD93025),
    errorContainer = Color(0xFFFFE1DE),
    onErrorContainer = Color(0xFF5C0A05)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF4FD8C4),
    onPrimary = Color(0xFF00302B),
    primaryContainer = Color(0xFF0F4C47),
    onPrimaryContainer = Color(0xFFB8F5EC),
    secondary = Color(0xFF9DAAFF),
    onSecondary = Color(0xFF0E1659),
    secondaryContainer = Color(0xFF2A3380),
    onSecondaryContainer = Color(0xFFDDE0FF),
    tertiary = Color(0xFFFFC857),
    background = Color(0xFF081312),
    onBackground = Color(0xFFE0F0EE),
    surface = Color(0xFF0F1D1C),
    onSurface = Color(0xFFE0F0EE),
    surfaceVariant = Color(0xFF1B2F2D),
    onSurfaceVariant = Color(0xFFA9C4C1),
    outline = Color(0xFF5E7C79),
    outlineVariant = Color(0xFF1F3533),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF5C1A15),
    onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun TvDeckTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content
    )
}
