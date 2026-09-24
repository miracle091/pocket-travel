package com.pockettravel.core.ui

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

// Componenti M3 Expressive (material3 1.5.0-alpha): l'opt-in sperimentale resta qui, le schermate
// usano solo quello che espone questo file.

// Attesa di durata indefinita (caricamenti, generazione dell'IA).
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PocketTravelLoadingIndicator(modifier: Modifier = Modifier) {
    LoadingIndicator(modifier = modifier)
}

// Avanzamento di un download (0..1).
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadProgressIndicator(progress: () -> Float, modifier: Modifier = Modifier) {
    LinearWavyProgressIndicator(progress = progress, modifier = modifier)
}

// Forma degli elementi hero (icona delle pagine dell'onboarding).
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val HeroShape: Shape
    @Composable get() = MaterialShapes.Cookie9Sided.toShape()
