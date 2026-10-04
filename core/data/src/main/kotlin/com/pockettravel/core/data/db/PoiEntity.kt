package com.pockettravel.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Indice su regionId: ogni query e ogni eliminazione dei POI filtra per regione. Quello su (regionId, lat)
// serve ai segnalini e ai piu' vicini, che oltre alla regione limitano la latitudine.
@Entity(tableName = "poi", indices = [Index("regionId"), Index("regionId", "lat")])
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
    // Solo per i POI con poiHasDetails (core:poi), null se OSM non li indica.
    val openingHours: String? = null,
    val address: String? = null,
    // Solo per alloggi, ambasciate e consolati (poiHasContacts, core:poi).
    val website: String? = null,
    val email: String? = null,
    // Ambasciate e consolati: paese rappresentato (ISO 3166-1 alpha-2), vedi GeneratePoi.kt.
    val country: String? = null,
    // Nome in inglese e in italiano (OSM name:en, name:it), solo se diverso da name: vedi Poi.displayName.
    val nameEn: String? = null,
    val nameIt: String? = null,
    // Tag OSM "toilets:wheelchair" (yes, limited, no...): bagni accessibili di un POI che ha dei bagni.
    val toiletsWheelchair: String? = null,
    // Tag OSM "capacity:disabled": posti auto per disabili di un parcheggio, null se non indicati.
    val capacityDisabled: Int? = null,
    // true per i POI del pacchetto extra (poi-extra.db): si importano e si eliminano a parte.
    @ColumnInfo(defaultValue = "0") val extra: Boolean = false,
)
