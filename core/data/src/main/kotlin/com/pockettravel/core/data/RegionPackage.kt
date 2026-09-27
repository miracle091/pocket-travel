package com.pockettravel.core.data

/** Una regione con almeno un pacchetto installato; la versione e' null per un pacchetto assente. */
data class RegionPackage(
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
    // Default null: non tocca le costruzioni esistenti (test in app/) che non la conoscono ancora.
    val previewVersion: String? = null,
) {
    fun versionOf(kind: PackageKind): String? = when (kind) {
        PackageKind.MAP -> mapVersion
        PackageKind.ROUTING -> routingVersion
        PackageKind.POI -> poiVersion
        PackageKind.POI_EXTRA -> poiExtraVersion
        PackageKind.ADDRESSES -> addressesVersion
    }
}
