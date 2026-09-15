package com.pockettravel.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Seed = Color(0xFF00677E)

private val LightColors = lightColorScheme(
    primary = Seed,
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF4A6367),
    tertiary = Color(0xFF5C5D7E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D3EC),
    onPrimary = Color(0xFF00363F),
    secondary = Color(0xFFB1CBD0),
    tertiary = Color(0xFFC5C4EA),
)

// Unico punto in cui l'app dichiara il proprio color scheme: sostituisce il MaterialTheme
// nudo (default Material-You) cosi' tutte le schermate ereditano gli stessi colori.
@Composable
fun PocketTravelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
