package com.pockettravel.core.data

/** Una regione con almeno un pacchetto installato; la versione e' null per un pacchetto assente. */
data class RegionPackage(
    val regionId: String,
    val displayName: String,
    val mapVersion: String?,
    val routingVersion: String?,
    val poiVersion: String?,
    val poiSizeBytes: Long?,
    val sizeBytes: Long,
) {
    fun versionOf(kind: PackageKind): String? = when (kind) {
        PackageKind.MAP -> mapVersion
        PackageKind.ROUTING -> routingVersion
        PackageKind.POI -> poiVersion
    }
}
