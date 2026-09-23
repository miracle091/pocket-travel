package com.pockettravel.core.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// Catalogo dei token del tema (colori del brand senza dynamic color, POI, type scale), per
// controllare a colpo d'occhio chiaro/scuro in Android Studio. Nessun uso a runtime.
@Preview(name = "Chiaro", widthDp = 360)
@Preview(name = "Scuro", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ThemeTokensPreview() {
    PocketTravelTheme(dynamicColor = false) {
        Surface {
            val c = MaterialTheme.colorScheme
            Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Swatch("primary", c.primary, c.onPrimary)
                Swatch("primaryContainer", c.primaryContainer, c.onPrimaryContainer)
                Swatch("secondaryContainer", c.secondaryContainer, c.onSecondaryContainer)
                Swatch("tertiaryContainer", c.tertiaryContainer, c.onTertiaryContainer)
                Swatch("errorContainer", c.errorContainer, c.onErrorContainer)
                Swatch("surfaceContainer", c.surfaceContainer, c.onSurface)
                Swatch("surfaceContainerHighest", c.surfaceContainerHighest, c.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    listOf(
                        PoiColors.Lodging, PoiColors.FoodDrink, PoiColors.Shopping,
                        PoiColors.Attractions, PoiColors.Embassy, PoiColors.Other,
                    ).forEach { Surface(Modifier.size(32.dp), shape = CircleShape, color = it) {} }
                }
                val t = MaterialTheme.typography
                Text("headlineMedium", style = t.headlineMedium)
                Text("titleLarge", style = t.titleLarge)
                Text("titleMedium", style = t.titleMedium)
                Text("bodyLarge — testo della guida", style = t.bodyLarge)
                Text("bodySmall — fonte Wikivoyage", style = t.bodySmall)
                Text("labelLarge", style = t.labelLarge)
            }
        }
    }
}

@Composable
private fun Swatch(name: String, container: Color, content: Color) {
    Text(
        text = name,
        color = content,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.fillMaxWidth().background(container, MaterialTheme.shapes.medium).padding(Spacing.m),
    )
}
