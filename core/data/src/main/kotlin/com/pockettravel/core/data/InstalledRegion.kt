package com.pockettravel.core.data

/** Una regione con almeno un pacchetto installato; la versione e' null per un pacchetto assente. */
data class InstalledRegion(
    val regionId: String,
    val displayName: String,
    val countryCode: String?,
    val mapVersion: String?,
    val routingVersion: String?,
    val poiVersion: String?,
    val poiExtraVersion: String?,
    val addressesVersion: String?,
    val poiSizeBytes: Long?,
    val poiExtraSizeBytes: Long?,
    val sizeBytes: Long,
    // Anteprima offline installata (preview.pmtiles): non e' un PackageKind, vedi InstalledRegionEntity.
    // Default null: le costruzioni che non la indicano (test in app/) restano valide.
    val previewVersion: String? = null,
    // Guide delle citta' (city_sections in region.db, non misurabili dal disco come mappa e routing,
    // vedi citiesSizeBytes). Default null, come previewVersion.
    val citiesVersion: String? = null,
    val citiesSizeBytes: Long? = null,
    // Orari dei mezzi pubblici (cartella transit): la dimensione si misura dal disco. Default null come citiesVersion.
    val transitVersion: String? = null,
) {
    fun versionOf(kind: PackageKind): String? = when (kind) {
        PackageKind.MAP -> mapVersion
        PackageKind.ROUTING -> routingVersion
        PackageKind.POI -> poiVersion
        PackageKind.POI_EXTRA -> poiExtraVersion
        PackageKind.ADDRESSES -> addressesVersion
        PackageKind.CITIES -> citiesVersion
        PackageKind.TRANSIT -> transitVersion
    }
}
