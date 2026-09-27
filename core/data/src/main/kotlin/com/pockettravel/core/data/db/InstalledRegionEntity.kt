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
    val poiExtraVersion: String?,
    val addressesVersion: String?,
    // Anteprima offline (preview.pmtiles, pochi zoom): si installa e aggiorna da sola con ogni
    // download della regione (RegionPackageInstaller), non e' un pacchetto a parte per l'utente -
    // sparisce solo con l'intera regione, mai con "elimina mappa".
    val previewVersion: String?,
    // Byte del poi.db importato: i POI stanno in region.db, non misurabili dal disco come mappa e routing.
    val poiSizeBytes: Long?,
    // Come poiSizeBytes, per il poi-extra.db importato.
    val poiExtraSizeBytes: Long?,
    // Totale sul device: mappa + routing + civici (dal disco) + poiSizeBytes + poiExtraSizeBytes.
    val sizeBytes: Long,
    val installedAt: Long,
)
