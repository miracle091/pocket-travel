package com.pockettravel.feature.map

import com.pockettravel.core.poi.PoiCategory

data class MapPin(
    val id: String,
    // null se il POI non ha un nome in OSM: la scheda mostra la categoria.
    val name: String?,
    val latitude: Double,
    val longitude: Double,
    val category: PoiCategory,
    // Tag OSM ("amenity=restaurant"): da qui il tipo preciso mostrato nella scheda (poiTypeLabel).
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
)
