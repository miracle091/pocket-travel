package com.pockettravel.core.data

import com.pockettravel.core.data.db.DiplomaticMissionDao
import com.pockettravel.core.data.db.DiplomaticMissionEntity
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject

class DiplomaticMissionRepository @Inject constructor(private val diplomaticMissionDao: DiplomaticMissionDao) {
    /**
     * Ambasciate e consolati (da Wikidata) del paese [sending] nel paese [host], entrambi ISO 3166-1 alpha-2
     * in qualunque maiuscolo. Vuoto se guides.db non porta la tabella.
     */
    suspend fun missions(sending: String, host: String): List<DiplomaticMission> =
        diplomaticMissionDao.missions(sending.lowercase(Locale.ROOT), host.lowercase(Locale.ROOT)).map { it.toDomain() }
}

/** Tipo di rappresentanza, nell'ordine in cui la guida le elenca. */
enum class MissionKind {
    EMBASSY,
    CONSULATE_GENERAL,
    CONSULATE,
}

data class DiplomaticMission(
    val wikidata: String,
    val kind: MissionKind,
    val name: String,
    val nameEn: String?,
    val city: String?,
    val address: String?,
    val phone: String?,
    val website: String?,
    val email: String?,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/** Riga del riquadro emergenze: un POI OSM, una rappresentanza Wikidata o le due fuse. [kind] e' null per i POI OSM senza corrispondenza. */
data class EmbassyEntry(
    val name: String,
    val kind: MissionKind?,
    val phone: String?,
    val address: String?,
    val website: String?,
    val email: String?,
    // Posizione, se nota (sempre per i POI OSM, poche volte da Wikidata): per "Vicino a te".
    val latitude: Double? = null,
    val longitude: Double? = null,
)

private fun DiplomaticMissionEntity.toDomain() = DiplomaticMission(
    wikidata, missionKindOf(kind), name, nameEn, city, address, phone, website, email, lat, lon,
)

// Un tipo che questa build non conosce (pipeline piu' recente) vale come consolato, il piu' generico.
private fun missionKindOf(kind: String) = when (kind) {
    "embassy" -> MissionKind.EMBASSY
    "consulate_general" -> MissionKind.CONSULATE_GENERAL
    else -> MissionKind.CONSULATE
}

/**
 * Fonde i POI OSM della regione ([osm]) con le rappresentanze Wikidata dello stesso paese ([missions]):
 * quelle gia' presenti su OSM (stesso telefono o nome simile) non si ripetono e completano il POI con il
 * tipo e con i recapiti che OSM non ha. Ordine: tipo (i POI OSM senza corrispondenza, di tipo ignoto,
 * per primi), poi quelle in una citta' della regione ([regionCities]), poi per nome. [language] ("en"/"it")
 * sceglie il nome.
 */
fun mergeEmbassies(osm: List<Poi>, missions: List<DiplomaticMission>, regionCities: Collection<String>, language: String): List<EmbassyEntry> {
    val cities = regionCities.map { it.folded() }.toSet()
    // Ordine fisso, cosi' a parita' di somiglianza vince sempre la stessa rappresentanza.
    val unmatched = missions.sortedWith(compareBy({ it.kind.ordinal }, { it.wikidata })).toMutableList()
    val fromOsm = osm.map { poi ->
        // Una sola rappresentanza per POI: prima lo stesso telefono, poi un nome simile dello stesso tipo
        // (un consolato "d'Italia" non e' l'ambasciata "d'Italia").
        val poiKind = poi.missionKind()
        val match = unmatched.firstOrNull { samePhone(it.phone, poi.phone) }
            ?: unmatched.firstOrNull { (poiKind == null || it.kind == poiKind) && it.sameNameAs(poi, sameKind = poiKind != null) }
        match?.let(unmatched::remove)
        EmbassyEntry(
            name = poi.displayName(language),
            kind = match?.kind ?: poiKind,
            phone = poi.phone ?: match?.phone,
            address = poi.address ?: match?.address,
            website = poi.website ?: match?.website,
            email = poi.email ?: match?.email,
            latitude = poi.latitude,
            longitude = poi.longitude,
        ) to true
    }
    val fromWikidata = unmatched.map { mission ->
        EmbassyEntry(
            name = mission.displayName(language),
            kind = mission.kind,
            phone = mission.phone,
            address = mission.address,
            website = mission.website,
            email = mission.email,
            latitude = mission.latitude,
            longitude = mission.longitude,
        ) to (mission.city?.folded()?.let { it in cities } == true)
    }
    return (fromOsm + fromWikidata)
        .sortedWith(compareBy({ it.first.kind?.ordinal ?: -1 }, { if (it.second) 0 else 1 }, { it.first.name.folded() }))
        .map { it.first }
}

/**
 * Le rappresentanze con posizione nota entro [maxKm] da ([latitude], [longitude]), dalla piu' vicina e al
 * massimo [limit], con la distanza in km: la parte "Vicino a te" del riquadro emergenze.
 */
fun nearbyEmbassies(
    entries: List<EmbassyEntry>,
    latitude: Double,
    longitude: Double,
    maxKm: Double = NEARBY_MAX_KM,
    limit: Int = NEARBY_LIMIT,
): List<Pair<EmbassyEntry, Double>> =
    entries.mapNotNull { entry ->
        val lat = entry.latitude ?: return@mapNotNull null
        val lon = entry.longitude ?: return@mapNotNull null
        entry to distanceKm(latitude, longitude, lat, lon)
    }.filter { it.second <= maxKm }.sortedBy { it.second }.take(limit)

// Oltre i 100 km non e' "vicino": il riquadro mostra solo l'elenco del paese.
private const val NEARBY_MAX_KM = 100.0
private const val NEARBY_LIMIT = 3

private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2).let { it * it } +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
    return 2 * 6371.0 * Math.asin(Math.sqrt(a))
}

private fun DiplomaticMission.displayName(language: String): String =
    (if (language == "en") nameEn ?: name else name)

private fun DiplomaticMission.sameNameAs(poi: Poi, sameKind: Boolean): Boolean {
    val poiNames = listOfNotNull(poi.name, poi.nameEn, poi.nameIt)
    return listOfNotNull(name, nameEn).any { mine -> poiNames.any { sameName(mine, it, sameKind) } }
}

// Cifre sole, ultime 8 (il prefisso internazionale puo' mancare da una delle due fonti).
private fun samePhone(a: String?, b: String?): Boolean {
    val digitsA = a?.filter { it.isDigit() }.orEmpty()
    val digitsB = b?.filter { it.isDigit() }.orEmpty()
    if (digitsA.length < 7 || digitsB.length < 7) return false
    return digitsA.takeLast(8) == digitsB.takeLast(8)
}

// Stessi termini significativi, oppure tutti quelli del nome piu' corto nell'altro: ne bastano due, o uno
// solo se il tipo e' lo stesso ("Ambasciata d'Italia" e "Ambasciata d'Italia a Madrid").
private fun sameName(a: String, b: String, sameKind: Boolean): Boolean {
    val tokensA = a.significantTokens()
    val tokensB = b.significantTokens()
    if (tokensA.isEmpty() || tokensB.isEmpty()) return false
    if (tokensA == tokensB) return true
    val (shorter, longer) = if (tokensA.size <= tokensB.size) tokensA to tokensB else tokensB to tokensA
    return (shorter.size >= 2 || sameKind) && longer.containsAll(shorter)
}

// Le lettere sole sono elisioni ("d'Italia", "l'Aia") e non distinguono nulla.
private fun String.significantTokens(): Set<String> =
    folded().split(NON_WORD).filter { it.length > 1 && it !in GENERIC_WORDS }.toSet()

private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
private val COMBINING_MARKS = Regex("\\p{M}")

// Il tipo di un POI OSM dal nome (il tag non lo distingue): null se il nome non lo dice.
private fun Poi.missionKind(): MissionKind? {
    val names = listOfNotNull(name, nameEn, nameIt).joinToString(" ").folded()
    return when {
        CONSULATE_WORDS.any { it in names } -> if ("general" in names) MissionKind.CONSULATE_GENERAL else MissionKind.CONSULATE
        EMBASSY_WORDS.any { it in names } -> MissionKind.EMBASSY
        else -> null
    }
}

private val CONSULATE_WORDS = listOf("consol", "consul", "konsul")
private val EMBASSY_WORDS = listOf("embass", "ambasc", "embaj", "ambassad", "botschaft", "embaix")

// Parole che non distinguono una rappresentanza dall'altra, nelle lingue piu' comuni dei nomi.
private val GENERIC_WORDS = setOf(
    "embassy", "ambasciata", "embajada", "ambassade", "botschaft", "consulate", "consolato", "consulado", "consulat",
    "konsulat", "general", "generale", "honorary", "onorario", "of", "the", "de", "di", "del", "della", "la", "le",
    "el", "du", "des", "der", "von", "in", "a", "to", "en", "y", "et", "und", "and",
)

private fun String.folded(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(COMBINING_MARKS, "").lowercase(Locale.ROOT)
