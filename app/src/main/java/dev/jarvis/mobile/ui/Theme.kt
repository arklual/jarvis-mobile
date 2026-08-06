package dev.jarvis.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** «Клевер» настольного Jarvis: одна краска, остальное — оттенки бумаги. */
private val Clover = Color(0xFF0F5132)
private val CloverLight = Color(0xFF7BD389)

private val Light = lightColorScheme(
    primary = Clover,
    onPrimary = Color.White,
    surface = Color(0xFFFBFBF9),
    background = Color(0xFFFBFBF9),
)

private val Dark = darkColorScheme(
    primary = CloverLight,
    onPrimary = Color(0xFF06281A),
    surface = Color(0xFF14161A),
    background = Color(0xFF14161A),
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
