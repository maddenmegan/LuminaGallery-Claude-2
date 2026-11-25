package com.lumina.gallery.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Amoled = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF1B2A41),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFB39DDB),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF241C35),
    onSecondaryContainer = Color(0xFFE6DBFF),
    tertiary = Color(0xFF7FD6C2),
    background = Color.Black,
    onBackground = Color(0xFFE8EAED),
    surface = Color.Black,
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF16181C),
    onSurfaceVariant = Color(0xFFB0B4BA),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF08090B),
    surfaceContainer = Color(0xFF0C0D10),
    surfaceContainerHigh = Color(0xFF16181C),
    surfaceContainerHighest = Color(0xFF1E2126),
    outline = Color(0xFF3A3D43),
    outlineVariant = Color(0xFF25272B),
    error = Color(0xFFFF8A80)
)

@Composable
fun LuminaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Amoled, content = content)
}
