package com.pockettravel.feature.map

import com.pockettravel.core.poi.PoiCategory

data class MapPin(
    val id: String,
    // null se il POI non ha un nome in OSM: il riquadro mostra la categoria.
    val name: String?,
    val latitude: Double,
    val longitude: Double,
    val category: PoiCategory,
    // Tag OSM ("amenity=restaurant"): da qui il tipo preciso mostrato nel riquadro (poiTypeLabel).
    val osmTag: String,
    val phone: String?,
    // Tag OSM "wheelchair" (yes, limited, no...), null se non indicato.
    val wheelchair: String? = null,
    // Cibo e bevande: orari (opening_hours di OSM) e indirizzo, se indicati.
    val openingHours: String? = null,
    val address: String? = null,
    // Alloggi, ambasciate e consolati: sito ed email, se indicati.
    val website: String? = null,
    val email: String? = null,
    // Nomi in inglese e in italiano (OSM name:en, name:it), se diversi da [name].
    val nameEn: String? = null,
    val nameIt: String? = null,
    // Bagni accessibili (OSM "toilets:wheelchair": yes, limited, no...) e posti auto per disabili, se indicati.
    val toiletsWheelchair: String? = null,
    val capacityDisabled: Int? = null,
) {
    /** Nome nella lingua dell'interfaccia se OSM lo ha, altrimenti [name] (null senza nome). */
    fun displayName(language: String): String? = name?.let { (if (language == "en") nameEn else nameIt ?: nameEn) ?: it }
}
