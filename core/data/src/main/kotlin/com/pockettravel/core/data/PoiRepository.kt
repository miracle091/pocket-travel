package com.pockettravel.core.data

import com.pockettravel.core.data.db.CategoryTag
import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.db.PoiEntity
import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.core.poi.poiCategoryOf
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

private const val HIDDEN_OVERFETCH = 4

// Altezza dell'area (gradi di latitudine) oltre la quale i segnalini a campione si scelgono leggendo la regione in ordine di
// rowid (PoiDao.spreadInWideBounds) invece che per fascia di latitudine: a questa altezza la fascia di una nazione grande ha
// gia' decine di migliaia di righe sparse sul disco, piu' costose da leggere della tabella intera in sequenza.
private const val WIDE_AREA_DEGREES = 0.5

/** Il filtro "In sedia a rotelle" della mappa: tutti, senza i POI non accessibili, solo quelli accessibili. */
enum class MapAccessibility { ALL, NO_INACCESSIBLE, ONLY_ACCESSIBLE }

class PoiRepository @Inject constructor(private val poiDao: PoiDao) {
    /**
     * I POI della regione dentro il riquadro, per i segnalini della mappa, senza le categorie [hiddenCategories]: tutti se
     * sono al massimo [maxPois], altrimenti circa [maxPois] sparsi sull'area (uno per cella di una griglia). Le categorie
     * filtrate si escludono nella query, prima del conteggio e della scelta per cella: dopo, le celle avrebbero scelto
     * altri POI e quelli della categoria rimasta visibile sarebbero quasi spariti. [tags] sono le coppie della regione
     * ([categoryTags], lette una volta per regione da chi chiama): con i POI tornano le categorie presenti, anche quelle
     * filtrate, perche' l'utente possa riattivarle. Si chiede una riga in piu' di [maxPois]: se arrivano tutte, le celle;
     * cosi' non si conta tutta l'area (su una nazione grande, secondi a ogni fermo della mappa).
     *
     * I POI nascosti sulla mappa (isHiddenOnMap) non servono nella query: la pipeline non pubblica quelli nascosti, i
     * "base" non lo sono mai e gli "extra" si mostrano comunque. Il filtro in Kotlin resta per i dati vecchi.
     */
    suspend fun inBounds(
        regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, maxPois: Int, hiddenCategories: Set<PoiCategory> = emptySet(),
        accessibility: MapAccessibility = MapAccessibility.ALL,
        tags: List<CategoryTag> = emptyList(),
    ): AreaPois {
        val present = tags.mapTo(mutableSetOf()) { poiCategoryOf(it.category, it.osmTag) }
        val excluded = tags.filter { poiCategoryOf(it.category, it.osmTag) in hiddenCategories }.map { "${it.category}|${it.osmTag}" }
        val firstRows = poiDao.poisInBounds(regionId, minLat, maxLat, minLon, maxLon, excluded, accessibility.ordinal, maxPois + 1)
        val entities = if (firstRows.size <= maxPois) {
            firstRows
        } else {
            val side = sqrt(maxPois.toDouble())
            val spread = if (maxLat - minLat > WIDE_AREA_DEGREES) poiDao::spreadInWideBounds else poiDao::spreadInBounds
            spread(regionId, minLat, maxLat, minLon, maxLon, (maxLat - minLat) / side, (maxLon - minLon) / side, excluded, accessibility.ordinal)
        }
        return AreaPois(entities.map { it.toDomain() }, present)
    }

    /** Le coppie (category, osmTag) della regione, per [inBounds]: da leggere una volta per regione. */
    suspend fun categoryTags(regionId: String): List<CategoryTag> = poiDao.categoryTagsInRegion(regionId)

    /** Ambasciate e consolati di [country] (ISO alpha-2) nella regione, in ordine di nome. */
    suspend fun embassiesOf(regionId: String, country: String): List<Poi> = poiDao.embassiesOf(regionId, country).map { it.toDomain() }

    /**
     * POI delle [regionIds] col nome (locale, italiano o inglese) che contiene [query], senza quelli nascosti
     * sulla mappa: al piu' [limit], senza ordine (lo decide chi chiama, per esempio la distanza). Maiuscole e
     * accenti non contano: "riga" trova "Rīga" e "Rīga" trova "Riga".
     */
    suspend fun searchByName(regionIds: List<String>, query: String, limit: Int): List<Poi> {
        val text = query.trim()
        if (text.length < 2 || regionIds.isEmpty()) return emptyList()
        // Il LIMIT di SQL viene prima del filtro sui nascosti (regola in Kotlin, non esprimibile in SQL): si
        // chiede di piu' e si taglia dopo, cosi' i nascosti non svuotano il risultato.
        return poiDao.searchByName(regionIds, accentInsensitiveGlob(text), limit * HIDDEN_OVERFETCH)
            .map { it.toDomain() }.filterNot { it.isHiddenOnMap() }.take(limit)
    }

    /**
     * I POI attorno a ([lat], [lon]) per il Navigatore senza meta: entro 150 m, o 300 o 600 se piu' vicino ce ne sono
     * meno di 5 (in campagna la mappa non resta vuota); al massimo 30, i piu' vicini. Il raggio usato torna con i POI:
     * la mappa lo inquadra.
     */
    suspend fun nearby(regionIds: List<String>, lat: Double, lon: Double): NearbyPois {
        if (regionIds.isEmpty()) return NearbyPois(emptyList(), NEARBY_RADII_METERS.first())
        val maxRadius = NEARBY_RADII_METERS.last()
        val lonScale = cos(Math.toRadians(lat)).let { it * it }
        val dLat = maxRadius / METERS_PER_DEGREE
        val dLon = dLat / cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val found = poiDao.nearest(regionIds, lat, lon, lonScale, lat - dLat, lat + dLat, lon - dLon, lon + dLon, NEARBY_LIMIT * HIDDEN_OVERFETCH)
            .map { it.toDomain() }.filterNot { it.isHiddenOnMap() }
            .map { it to hypot(it.latitude - lat, (it.longitude - lon) * cos(Math.toRadians(lat))) * METERS_PER_DEGREE }
        val radius = nearbyRadius(found.map { it.second })
        return NearbyPois(found.filter { it.second <= radius }.take(NEARBY_LIMIT).map { it.first }, radius)
    }

    /** Quanti treni (stazioni), metro, autostazioni, porti e aeroporti ha la regione, per categoria. */
    suspend fun transportCounts(regionId: String): Map<PoiCategory, Int> =
        poiDao.transportCounts(regionId)
            .groupBy({ poiCategoryOf(it.category, it.osmTag) }, { it.count })
            .mapValues { (_, counts) -> counts.sum() }
}

/** I POI di un'area per i segnalini e le categorie che l'area contiene (anche quelle filtrate). */
data class AreaPois(val pois: List<Poi>, val categories: Set<PoiCategory>)

data class Poi(
    val id: Long,
    val regionId: String,
    val name: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val osmTag: String,
    val phone: String?,
    val wheelchair: String? = null,
    val openingHours: String? = null,
    val address: String? = null,
    val website: String? = null,
    val email: String? = null,
    val country: String? = null,
    val nameEn: String? = null,
    val nameIt: String? = null,
    // Dal pacchetto extra: la mappa li mostra anche se isHiddenOnMap() li nasconderebbe.
    val extra: Boolean = false,
    // Bagni accessibili (OSM "toilets:wheelchair") e posti auto per disabili ("capacity:disabled"), null se non indicati.
    val toiletsWheelchair: String? = null,
    val capacityDisabled: Int? = null,
)

private fun PoiEntity.toDomain() =
    Poi(
        id, regionId, name, category, lat, lon, osmTag, phone, wheelchair, openingHours, address, website, email, country, nameEn, nameIt, extra,
        toiletsWheelchair, capacityDisabled,
    )

/** Nome nella lingua dell'interfaccia ("en"/"it") se OSM lo ha, altrimenti quello locale. */
fun Poi.displayName(language: String): String = (if (language == "en") nameEn else nameIt ?: nameEn) ?: name

/** I POI attorno alla posizione e il raggio in cui sono stati cercati (metri). */
data class NearbyPois(val pois: List<Poi>, val radiusMeters: Int)

private val NEARBY_RADII_METERS = listOf(150, 300, 600)
private const val NEARBY_MIN_COUNT = 5
private const val NEARBY_LIMIT = 30
private const val METERS_PER_DEGREE = 111_320.0

/** Il raggio piu' piccolo con almeno 5 POI ([distancesMeters] dalla posizione), altrimenti il piu' grande. */
internal fun nearbyRadius(distancesMeters: List<Double>): Int =
    NEARBY_RADII_METERS.firstOrNull { radius -> distancesMeters.count { it <= radius } >= NEARBY_MIN_COUNT } ?: NEARBY_RADII_METERS.last()
