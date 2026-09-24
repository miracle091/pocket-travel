package com.pockettravel.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "installed_regions")
data class InstalledRegionEntity(
    @PrimaryKey val regionId: String,
    val displayName: String,
    // ISO 3166-1 alpha-2 dal manifest, per la bandiera; null per le regioni installate prima della
    // versione 8 del database finche' l'elenco regioni non lo riempie dal manifest.
    val countryCode: String?,
    val mapVersion: String?,
    val routingVersion: String?,
    val poiVersion: String?,
    val addressesVersion: String?,
    // Byte del poi.db importato: i POI stanno in region.db, non misurabili dal disco come mappa e routing.
    val poiSizeBytes: Long?,
    // Totale sul device: mappa + routing (dal disco) + poiSizeBytes.
    val sizeBytes: Long,
    val installedAt: Long,
)
