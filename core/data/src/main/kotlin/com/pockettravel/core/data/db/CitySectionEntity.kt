package com.pockettravel.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pockettravel.core.data.GuideCategory

// Indice composto su (regionId, city): CityRepository.citiesFor e sectionsFor filtrano sempre per
// entrambi (le citta' di piu' regioni possono avere lo stesso nome).
@Entity(tableName = "city_sections", indices = [Index(value = ["regionId", "city"])])
data class CitySectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val regionId: String,
    val city: String,
    val category: GuideCategory,
    val title: String,
    val body: String,
    val sourceUrl: String,
)
