package com.intrudersnap.prototype.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF0E8C82)
private val TealLight = Color(0xFF4FD1C5)
private val Navy = Color(0xFF0F1B2D)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    secondary = Navy,
    onSecondary = Color.White,
    background = Color(0xFFF6F8FA),
    surface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Navy,
    secondary = Color(0xFF9FB3C8),
    background = Color(0xFF0B1220),
    surface = Color(0xFF131C2B),
)

@Composable
fun IntruderSnapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
