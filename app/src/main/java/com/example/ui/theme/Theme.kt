package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFE50914),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF331114),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFE0E0E0),
    onSecondary = Color(0xFF1E1E1E),
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFEEEEEE),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFEEEEEE),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFCCCCCC),
    outline = Color(0xFF333333)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFE50914),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF424242),
    onSecondary = Color.White,
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF1A1A1A),
    surface = Color.White,
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFEEEEEE),
    onSurfaceVariant = Color(0xFF555555),
    outline = Color(0xFFCCCCCC)
)

@Composable
fun MangaReaderTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
