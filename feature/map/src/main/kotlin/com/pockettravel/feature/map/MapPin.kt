package com.pockettravel.feature.map

import com.pockettravel.core.data.PoiCategory

data class MapPin(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val category: PoiCategory,
    val phone: String?,
)
