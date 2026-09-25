package com.pockettravel.feature.map

import com.pockettravel.core.poi.PoiCategory

data class MapPin(
    val id: String,
    // null se il POI non ha un nome in OSM: la scheda mostra la categoria.
    val name: String?,
    val latitude: Double,
    val longitude: Double,
    val category: PoiCategory,
    val phone: String?,
    // Tag OSM "wheelchair" (yes, limited, no...), null se non indicato.
    val wheelchair: String? = null,
)
