package com.pockettravel.core.ui

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// Unico punto in cui l'app dichiara il proprio tema. Typography e Shapes restano quelli di
// default di material3, che coincidono con la type scale e la shape scale M3 (4/8/12/16/28 dp):
// le schermate li usano solo tramite MaterialTheme.typography / MaterialTheme.shapes.
// Tema Expressive con motion a molla (MotionScheme.expressive), come da piano M3.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PocketTravelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        // Il dynamic color di sistema segue gia' da solo l'impostazione di contrasto (Android 14+).
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> brandColorScheme(darkTheme, systemContrast(context))
    }
    MaterialExpressiveTheme(colorScheme = colorScheme, motionScheme = MotionScheme.expressive(), content = content)
}

private fun brandColorScheme(darkTheme: Boolean, contrast: Float): ColorScheme = when {
    contrast >= 0.66f -> if (darkTheme) darkSchemeHighContrast else lightSchemeHighContrast
    contrast >= 0.33f -> if (darkTheme) darkSchemeMediumContrast else lightSchemeMediumContrast
    else -> if (darkTheme) darkScheme else lightScheme
}

// Contrasto scelto dall'utente nelle impostazioni di sistema (Android 14+): da -1 a 1, 0 standard.
private fun systemContrast(context: Context): Float =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        context.getSystemService(UiModeManager::class.java).contrast
    } else {
        0f
    }
