package com.pockettravel.feature.map.testing

import com.pockettravel.core.data.db.CategoryTag
import com.pockettravel.core.data.db.PoiDao
import com.pockettravel.core.data.db.PoiEntity
import com.pockettravel.core.data.db.PoiRow
import com.pockettravel.core.data.db.TransportCount
import kotlinx.coroutines.CompletableDeferred

/** [PoiDao] in memoria: filtra [rows] per regione, rettangolo e categorie escluse, e registra le richieste. */
class FakePoiDao : PoiDao {
    class InBoundsCall(
        val regionId: String, val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double,
        val excluded: List<String>, val accessibility: Int, val limit: Int,
    )

    val rows = mutableListOf<PoiEntity>()
    val inBoundsCalls = mutableListOf<InBoundsCall>()
    var categoryTagReads = 0
        private set

    /** Se c'e' un blocco per la regione, `poisInBounds` aspetta che lo si sblocchi. */
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    val searches = mutableListOf<Pair<List<String>, String>>()
    val nearestRows = mutableListOf<PoiEntity>()

    override suspend fun poisInBounds(
        regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
        excluded: List<String>, accessibility: Int, limit: Int,
    ): List<PoiEntity> {
        inBoundsCalls += InBoundsCall(regionId, minLat, maxLat, minLon, maxLon, excluded, accessibility, limit)
        gates[regionId]?.await()
        return rows.filter {
            it.regionId == regionId && it.lat in minLat..maxLat && it.lon in minLon..maxLon && "${it.category}|${it.osmTag}" !in excluded
        }.take(limit)
    }

    override suspend fun searchByName(regionIds: List<String>, pattern: String, limit: Int): List<PoiEntity> {
        searches += regionIds to pattern
        val matcher = globToRegex(pattern)
        return rows.filter { poi ->
            poi.regionId in regionIds && !poi.extra && listOfNotNull(poi.name, poi.nameIt, poi.nameEn).any { matcher.matches(it) }
        }.take(limit)
    }

    override suspend fun nearest(
        regionIds: List<String>, lat: Double, lon: Double, lonScale: Double,
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, limit: Int,
    ): List<PoiEntity> = nearestRows.filter { it.regionId in regionIds }.take(limit)

    override suspend fun categoryTagsInRegion(regionId: String): List<CategoryTag> {
        categoryTagReads++
        return rows.filter { it.regionId == regionId }.map { CategoryTag(it.category, it.osmTag) }.distinct()
    }

    override suspend fun insertAll(pois: List<PoiRow>) = Unit
    override suspend fun poisForRegion(regionId: String): List<PoiEntity> = rows.filter { it.regionId == regionId }
    override suspend fun spreadInBounds(
        regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, cellLat: Double, cellLon: Double,
        excluded: List<String>, accessibility: Int,
    ): List<PoiEntity> = emptyList()

    override suspend fun spreadInWideBounds(
        regionId: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, cellLat: Double, cellLon: Double,
        excluded: List<String>, accessibility: Int,
    ): List<PoiEntity> = emptyList()

    override suspend fun transportCounts(regionId: String): List<TransportCount> = emptyList()
    override suspend fun embassiesOf(regionId: String, country: String): List<PoiEntity> = emptyList()
    override suspend fun deleteForRegion(regionId: String, extra: Boolean?) = Unit

    // GLOB di SQLite: '*' qualsiasi testo, '[...]' un carattere del gruppo.
    private fun globToRegex(glob: String): Regex {
        val out = StringBuilder()
        var inGroup = false
        for (c in glob) {
            when {
                inGroup -> if (c == ']') {
                    out.append(']')
                    inGroup = false
                } else {
                    out.append(if (c.isLetterOrDigit()) c.toString() else "\\" + c)
                }
                c == '[' -> { out.append('['); inGroup = true }
                c == '*' -> out.append(".*")
                else -> out.append(Regex.escape(c.toString()))
            }
        }
        return Regex(out.toString())
    }
}
