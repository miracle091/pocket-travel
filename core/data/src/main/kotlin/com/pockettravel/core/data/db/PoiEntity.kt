package com.pockettravel.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "poi")
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
    // true per i POI del pacchetto extra (poi-extra.db): si importano e si eliminano a parte.
    @ColumnInfo(defaultValue = "0") val extra: Boolean = false,
)
