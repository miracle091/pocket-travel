package com.pockettravel.core.data

import java.time.LocalDate

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
    // Mappa d'insieme installata (preview.pmtiles): non e' un PackageKind, vedi InstalledRegionEntity.
    // Default null: le costruzioni che non la indicano (test in app/) restano valide.
    val previewVersion: String? = null,
    // Guide delle citta' (city_sections in region.db, non misurabili dal disco come mappa e rete stradale,
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

/**
 * Versione che la migrazione dei POI (database 24 -> 25) scrive al posto di quella installata quando non ha abbastanza spazio per
 * convertirli: nessun catalogo la usa, quindi la nazione risulta da aggiornare e i punti di interesse si riscaricano.
 */
const val POI_TO_REDOWNLOAD_VERSION = "da-riscaricare"

/** I pacchetti POI che la migrazione ha tolto per mancanza di spazio: da riscaricare. */
fun InstalledRegion.poiToRedownload(): Set<PackageKind> =
    setOf(PackageKind.POI, PackageKind.POI_EXTRA).filterTo(mutableSetOf()) { versionOf(it) == POI_TO_REDOWNLOAD_VERSION }

/**
 * Il giorno della build di un pacchetto, dalla sua versione ("2026.11.05.<run>.<tentativo>", come la scrive publish-regions);
 * null per le versioni di un altro formato.
 */
fun packageDate(version: String?): LocalDate? {
    val parts = version?.split('.')?.takeIf { it.size >= 3 } ?: return null
    return runCatching { LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt()) }.getOrNull()
}

/** Oltre questa eta' la rete stradale si dice vecchia: le strade aperte o chiuse dopo mancano (decisione dell'utente, 2026-10-25). */
const val ROUTING_STALE_MONTHS = 3L

/** Il giorno dei dati della rete stradale se a [today] ha piu' di [ROUTING_STALE_MONTHS] mesi, altrimenti null. */
fun staleRoutingDate(version: String?, today: LocalDate): LocalDate? = packageDate(version)?.takeIf { routingIsStale(it, today) }

/** Dati della rete stradale del giorno [date] con piu' di [ROUTING_STALE_MONTHS] mesi a [today]. */
fun routingIsStale(date: LocalDate, today: LocalDate): Boolean = date < today.minusMonths(ROUTING_STALE_MONTHS)

/** Il giorno dei dati del pacchetto [kind] di [region] se e' la rete stradale installata, altrimenti null. */
fun routingDate(region: InstalledRegion?, kind: PackageKind): LocalDate? = if (kind == PackageKind.ROUTING) packageDate(region?.routingVersion) else null
