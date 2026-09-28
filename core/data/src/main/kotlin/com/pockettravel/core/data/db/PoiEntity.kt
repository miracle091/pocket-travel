package com.pockettravel.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Indice su regionId: ogni query e ogni eliminazione dei POI filtra per regione.
@Entity(tableName = "poi", indices = [Index("regionId")])
data class PoiEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val regionId: String,
    val name: String,
    val category: String,
    val lat: Double,
    val lon: Double,
    val osmTag: String,
    val phone: String? = null,
    // Tag OSM "wheelchair" (yes, limited, no...), null se non indicato.
    val wheelchair: String? = null,
    // Solo per cibo e bevande (poiHasDetails, core:poi), null se OSM non li indica.
    val openingHours: String? = null,
    val address: String? = null,
    // true per i POI del pacchetto extra (poi-extra.db): si importano e si eliminano a parte.
    @ColumnInfo(defaultValue = "0") val extra: Boolean = false,
)
