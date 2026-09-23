package com.pockettravel.core.ui

import androidx.compose.ui.graphics.Color

// Colori fissi dei segnalini POI sulla mappa, uguali in tema chiaro e scuro e indipendenti dal
// dynamic color: una categoria deve avere sempre lo stesso colore. Tinte di riferimento in stile
// Google Maps, armonizzate col seed del brand (Blend.harmonize di material-color-utilities, solo
// per le tinte cromatiche) e scurite quanto basta perche' il glifo bianco sopra abbia contrasto
// >= 3:1 (WCAG 1.4.11, elementi grafici).
object PoiColors {
    val Glyph = Color(0xFFFFFFFF)
    val Lodging = Color(0xFFC9409D)
    val FoodDrink = Color(0xFFD37F00)
    val Shopping = Color(0xFF007BC5)
    val Attractions = Color(0xFF007F5E)
    val Embassy = Color(0xFF5F6368)
    val Other = Color(0xFF80868B)
}
