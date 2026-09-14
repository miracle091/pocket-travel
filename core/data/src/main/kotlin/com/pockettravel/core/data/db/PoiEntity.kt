package com.pockettravel.core.data.db

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
)
