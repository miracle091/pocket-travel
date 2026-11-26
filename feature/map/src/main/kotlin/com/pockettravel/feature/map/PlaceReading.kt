package com.pockettravel.feature.map

/** Cosa si legge in un testo che puo' contenere un posto: coordinate, URI `geo:`, link di Google Maps o OpenStreetMap. */
sealed interface PlaceReading {
    /** Un punto, con il nome che il testo gli da' (null se non ne ha). */
    data class Coordinates(val latitude: Double, val longitude: Double, val name: String? = null) : PlaceReading {
        val point: RoutePoint get() = RoutePoint(latitude, longitude)
    }

    /** Un `geo:0,0?q=testo`: nessuna coordinata, un indirizzo o un nome da cercare. */
    data class Search(val text: String) : PlaceReading

    /** Link abbreviato di Google (goo.gl): per leggerlo serve la rete. */
    data object ShortLink : PlaceReading

    data object Unreadable : PlaceReading
}
