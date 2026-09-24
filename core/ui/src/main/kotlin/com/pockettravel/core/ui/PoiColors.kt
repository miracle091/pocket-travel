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
    val Police = Color(0xFF33449C)
    val Toilets = Color(0xFF7B4FBF)
    val Fuel = Color(0xFFC5221F)
    val Parking = Color(0xFF1967D2)
    val PrivateParking = Color(0xFF5F6B7A)
    // Un solo colore per treno, metro, autobus e aeroporto (come Google Maps): il tipo lo dice il glifo.
    val Transit = Color(0xFF00707A)
    val Other = Color(0xFF80868B)
}
