package com.pockettravel.pipeline

import java.io.File
import java.sql.DriverManager

/** Chiavi di tag OSM riconosciute come punti di interesse — sottoinsieme minimo, non lo
 *  schema POI completo di OSM (amenity/shop/tourism/leisure/historic coprono la maggior
 *  parte dei casi comuni per una guida di viaggio). */
private val poiTagKeys = listOf("amenity", "shop", "tourism", "leisure", "historic")

fun main(args: Array<String>) {
    require(args.size == 3) { "Uso: generatePoi <input.osm.xml> <regionId> <output content.db>" }
    val inputXml = File(args[0])
    val regionId = args[1]
    val outputDb = File(args[2])

    val pois = extractPois(parseOsmXml(inputXml))

    writePoiDb(pois, regionId, outputDb)
    println("poi: ${pois.size} POI scritti in ${outputDb.path}")
}

data class Poi(val name: String, val category: String, val lat: Double, val lon: Double, val osmTag: String)

fun extractPois(data: OsmData): List<Poi> = data.nodes.mapNotNull { node ->
    val tagKey = poiTagKeys.firstOrNull { node.tags.containsKey(it) } ?: return@mapNotNull null
    val tagValue = node.tags.getValue(tagKey)
    Poi(
        name = node.tags["name"] ?: tagValue,
        category = tagValue,
        lat = node.lat,
        lon = node.lon,
        osmTag = "$tagKey=$tagValue",
    )
}

/**
 * Schema minimo (non lo schema Room di PoiEntity): una tabella "poi" con le stesse colonne
 * meno l'id autogenerato, che l'app importa riga per riga in region.db via PoiDao.insertAll().
 *
 * outputDb e' content.db, condiviso con la tabella "guide_sections" scritta da
 * GenerateGuideContent.kt — vedi il commento li' per il perche' del file unico.
 */
fun writePoiDb(pois: List<Poi>, regionId: String, outputDb: File) {
    DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
        conn.createStatement().use { statement ->
            statement.execute("DROP TABLE IF EXISTS poi")
            statement.execute(
                """
                CREATE TABLE poi (
                    regionId TEXT NOT NULL,
                    name TEXT NOT NULL,
                    category TEXT NOT NULL,
                    lat REAL NOT NULL,
                    lon REAL NOT NULL,
                    osmTag TEXT NOT NULL
                )
                """.trimIndent()
            )
        }
        conn.prepareStatement("INSERT INTO poi (regionId, name, category, lat, lon, osmTag) VALUES (?, ?, ?, ?, ?, ?)").use { insert ->
            pois.forEach { poi ->
                insert.setString(1, regionId)
                insert.setString(2, poi.name)
                insert.setString(3, poi.category)
                insert.setDouble(4, poi.lat)
                insert.setDouble(5, poi.lon)
                insert.setString(6, poi.osmTag)
                insert.addBatch()
            }
            insert.executeBatch()
        }
    }
}
