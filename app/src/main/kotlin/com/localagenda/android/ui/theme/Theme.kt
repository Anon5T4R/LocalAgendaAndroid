package com.localagenda.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cores da marca (mesmas do desktop LocalAgenda — App.css): azul #2563eb.
val AgendaBlue = Color(0xFF2563EB)
val AgendaBlueDark = Color(0xFF1D4ED8)
val AgendaBlueLight = Color(0xFFE8F0FE)

private val LightColors = lightColorScheme(
    primary = AgendaBlue,
    onPrimary = Color.White,
    primaryContainer = AgendaBlueLight,
    onPrimaryContainer = AgendaBlueDark,
    secondary = AgendaBlueDark,
    onSecondary = Color.White,
    background = Color(0xFFFBFBFF),
    surface = Color(0xFFFBFBFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4F8CFF),
    onPrimary = Color(0xFF0B1F3F),
    primaryContainer = Color(0xFF1B2A44),
    onPrimaryContainer = AgendaBlueLight,
    secondary = Color(0xFF4F8CFF),
    onSecondary = Color(0xFF0B1F3F),
    background = Color(0xFF10141B),
    surface = Color(0xFF10141B),
)

@Composable
fun LocalAgendaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
