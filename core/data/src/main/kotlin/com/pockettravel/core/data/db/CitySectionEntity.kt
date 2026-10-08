package com.pockettravel.core.data.db

import androidx.room.ColumnInfo
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
    // Abitanti della citta' (pipeline: Abitanti di Wikivoyage o Wikidata), null se ignoti o da un cities.db vecchio.
    val population: Long? = null,
    // Capitale della regione (pipeline: Wikidata P36), false da un cities.db vecchio.
    @ColumnInfo(defaultValue = "0") val capital: Boolean = false,
    // Coordinate della citta' (pipeline: Wikidata P625), null se ignote o da un cities.db vecchio.
    val latitude: Double? = null,
    val longitude: Double? = null,
    // Tradotta automaticamente dalla pagina nell'altra lingua (pipeline: colonna translated di cities.db).
    @ColumnInfo(defaultValue = "0") val translated: Boolean = false,
)
