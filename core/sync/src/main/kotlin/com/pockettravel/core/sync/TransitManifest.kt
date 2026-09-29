package com.pockettravel.core.sync

import com.pockettravel.core.data.TransitFeedInfo
import kotlinx.serialization.Serializable
import java.net.URI
import java.security.MessageDigest
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Voce "transit" in cima al manifest: riferimento a transit.json, scaricato e verificato (sha256)
 * come address-grid.json, vedi TransitClient.
 */
@Serializable
data class TransitManifestEntry(val version: String, val url: String, val sizeBytes: Long, val sha256: String)

/**
 * transit.json: le reti dei mezzi pubblici (una transit.db GTFS l'una) con le regioni che servono.
 * Della pipeline si leggono solo i campi che servono all'app (ignoreUnknownKeys): il riquadro, il
 * numero di fermate e la scadenza degli orari stanno gia' dentro la transit.db.
 */
@Serializable
data class TransitIndex(val version: String, val feeds: List<TransitFeed>)

/**
 * Una rete: [file] e' la transit.db, [fileXz], se c'e', il file da scaricare, compresso con xz, il cui
 * risultato decompresso deve avere dimensione e sha256 di [file] (stesso schema di [PoiPackageEntry]).
 * Tutte le reti chiamano il file "transit.db": in staging prendono il nome "<id>.db" (vedi [stagedFile]).
 */
@Serializable
data class TransitFeed(
    val id: String,
    val name: String,
    val regions: List<String>,
    val license: String,
    val attribution: String,
    val licenseUrl: String? = null,
    val version: String,
    val file: RegionManifestFile,
    val fileXz: RegionManifestFile? = null,
    // Riquadro delle fermate: minLon, minLat, maxLon, maxLat (build-transit.sh); per le reti vicine.
    val bbox: List<Double>? = null,
) {
    val downloadFile: RegionManifestFile get() = fileXz ?: file
}

/**
 * Reti di una regione: vedi [RegionManifestEntry.transit]. [feeds] sono quelle da scaricare (scelte
 * dall'utente), [available] tutte quelle che la regione offre.
 */
@Serializable
data class RegionTransitEntry(
    val feeds: List<TransitFeed>,
    val available: List<TransitFeed> = feeds,
    // Perche' [feeds] sono quelle di default (l'utente non ha ancora scelto); null se ha scelto o se le reti sono poche.
    val defaultReason: TransitDefaultReason? = null,
)

internal val TransitFeed.stagedFile: RegionManifestFile get() = file.copy(name = "$id.db")
internal val TransitFeed.stagedFileXz: RegionManifestFile? get() = fileXz?.copy(name = "$id.db.xz")
internal val TransitFeed.stagedDownloadFile: RegionManifestFile get() = stagedFileXz ?: stagedFile

internal fun TransitFeed.info() = TransitFeedInfo(id, name, attribution, licenseUrl)

fun TransitManifestEntry.validate() {
    require(isSafeVersion(version)) { "version dell'indice dei mezzi pubblici non valida" }
    require(sizeBytes >= 0) { "Dimensione dell'indice dei mezzi pubblici non valida" }
    require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "SHA-256 dell'indice dei mezzi pubblici non valido" }
    require(isAllowedManifestUrl(url)) { "url dell'indice dei mezzi pubblici non consentito" }
}

/** Convalida transit.json: id delle reti unici e sicuri come nome di file, regioni e file come per gli altri pacchetti. */
fun TransitIndex.validate() {
    require(isSafeVersion(version)) { "version dell'indice dei mezzi pubblici non valida" }
    require(feeds.map { it.id }.toSet().size == feeds.size) { "id di rete duplicati" }
    feeds.forEach { it.validate() }
}

internal fun TransitFeed.validate() {
    require(isSafeSegment(id)) { "id di rete non valido: $id" }
    require(bbox == null || bbox.size == 4) { "riquadro non valido per la rete $id" }
    require(isSafeVersion(version)) { "version non valida per la rete $id" }
    require(name.isNotBlank() && attribution.isNotBlank()) { "nome o attribuzione mancanti per la rete $id" }
    require(regions.all(::isSafeSegment)) { "regione non valida per la rete $id" }
    // Solo un link da aprire, non un file da scaricare: basta che sia https.
    require(licenseUrl == null || runCatching { URI(licenseUrl) }.getOrNull()?.let { it.scheme == "https" && it.host != null } == true) {
        "licenseUrl non valido per la rete $id"
    }
    file.validate("transit/$id")
    fileXz?.validate("transit/$id")
}

/** Le reti di [index] che servono la regione [regionId]. */
fun regionTransitFeeds(index: TransitIndex, regionId: String): List<TransitFeed> = index.feeds.filter { regionId in it.regions }

/**
 * Versione degli orari di una regione dalle sue reti: "transit-" + i primi 16 esadecimali dello
 * SHA-256 di "id@version" delle reti, uno per riga, ordinati per id — cosi' il confronto di versione
 * gia' esistente (outdatedKinds) vede un aggiornamento quando una rete cambia, si aggiunge o sparisce.
 */
fun regionTransitVersion(feeds: List<TransitFeed>): String {
    val lines = feeds.map { "${it.id}@${it.version}" }.sorted().joinToString("\n")
    val digest = MessageDigest.getInstance("SHA-256").digest(lines.toByteArray(Charsets.UTF_8))
    return "transit-" + digest.joinToString("") { "%02x".format(it) }.take(16)
}

/**
 * Arricchisce [entries] con le reti dei mezzi pubblici: le regioni senza reti restano invariate;
 * [index] null (manifest senza transit, o non scaricato) le lascia tutte invariate. [excluded]: le reti
 * tolte dall'utente per regione; se le toglie tutte restano tutte (l'app non lo permette).
 */
fun attachTransitFeeds(
    entries: List<RegionManifestEntry>,
    index: TransitIndex?,
    excluded: Map<String, Set<String>> = emptyMap(),
    defaultReasons: Map<String, TransitDefaultReason> = emptyMap(),
): List<RegionManifestEntry> {
    if (index == null) return entries
    return entries.map { entry ->
        val all = regionTransitFeeds(index, entry.regionId)
        val chosen = all.filter { it.id !in excluded[entry.regionId].orEmpty() }.ifEmpty { all }
        if (all.isEmpty()) entry else entry.copy(transit = RegionTransitEntry(chosen, all, defaultReasons[entry.regionId]))
    }
}

/** Oltre questo numero di reti in una regione si propongono solo quelle vicine (Regno Unito: 12 aree). */
private const val MANY_TRANSIT_FEEDS = 3

/** Entro questa distanza dal riquadro delle fermate una rete conta come vicina. */
private const val NEAR_TRANSIT_KM = 50.0

/** Come sono state scelte le reti di default di una regione con molte reti (mostrato nei Contenuti). */
@Serializable
enum class TransitDefaultReason {
    /** Quelle entro [NEAR_TRANSIT_KM] dalla posizione. */
    NEAR,

    /** Nessuna entro [NEAR_TRANSIT_KM]: la piu' vicina. */
    NEAREST,

    /** Posizione non nota o in un'altra nazione: tutte. */
    ALL,
}

/** Le reti tolte di default e il perche' ([reason] null con poche reti: si scaricano tutte, niente da spiegare). */
data class TransitDefault(val excluded: Set<String>, val reason: TransitDefaultReason?)

/**
 * La scelta di default quando l'utente non ha ancora scelto: con piu' di [MANY_TRANSIT_FEEDS] reti restano
 * quelle entro [NEAR_TRANSIT_KM] dalla posizione, o la piu' vicina se nessuna lo e'. La posizione va passata
 * solo se e' nella nazione della regione: senza (o in un'altra nazione) restano tutte.
 */
fun defaultTransitChoice(available: List<TransitFeed>, latitude: Double?, longitude: Double?): TransitDefault {
    if (available.size <= MANY_TRANSIT_FEEDS) return TransitDefault(emptySet(), null)
    if (latitude == null || longitude == null) return TransitDefault(emptySet(), TransitDefaultReason.ALL)
    val distances = available.mapNotNull { feed -> feed.bbox?.let { feed.id to distanceToBoxKm(it, latitude, longitude) } }
    if (distances.isEmpty()) return TransitDefault(emptySet(), TransitDefaultReason.ALL)
    val near = distances.filter { it.second <= NEAR_TRANSIT_KM }.map { it.first }
    val kept = near.ifEmpty { listOf(distances.minBy { it.second }.first) }
    return TransitDefault(
        available.map { it.id }.toSet() - kept.toSet(),
        if (near.isEmpty()) TransitDefaultReason.NEAREST else TransitDefaultReason.NEAR,
    )
}

// Distanza approssimata (equirettangolare) dal punto al riquadro, 0 se ci sta dentro.
private fun distanceToBoxKm(box: List<Double>, latitude: Double, longitude: Double): Double {
    val dLat = latitude - latitude.coerceIn(box[1], box[3])
    val dLon = (longitude - longitude.coerceIn(box[0], box[2])) * cos(Math.toRadians(latitude))
    return sqrt(dLat * dLat + dLon * dLon) * 111.32
}
