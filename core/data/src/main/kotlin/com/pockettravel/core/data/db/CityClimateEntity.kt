package com.pockettravel.core.data.db

import androidx.room.Entity

// Medie mensili della tabella del clima di Wikipedia (pipeline: tabella city_climate di cities.db). La chiave
// (regionId, city, month) copre anche le ricerche per (regionId, city): niente indice a parte.
@Entity(tableName = "city_climate", primaryKeys = ["regionId", "city", "month"])
data class CityClimateEntity(
    val regionId: String,
    val city: String,
    // Da 1 (gennaio) a 12.
    val month: Int,
    // Gradi Celsius: massima e minima media del mese, null se la tabella non le ha.
    val tempMax: Double? = null,
    val tempMin: Double? = null,
    // Pioggia totale del mese in millimetri e giorni di pioggia, null se assenti.
    val precipMm: Double? = null,
    val rainDays: Double? = null,
    val sourceUrl: String,
)
