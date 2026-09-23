package com.pockettravel.pipeline

import java.io.File

fun main(args: Array<String>) {
    require(args.size >= 4) { "Uso: generatePoi <regionId> <output poi.db> <poiTagKeys separate da virgola> <input1.osm.xml> [input2.osm.xml ...]" }
    val regionId = args[0]
    val outputDb = File(args[1])
    // Passate da build-region.sh (unica fonte di verita', usata anche per costruire la query
    // Overpass) invece di essere ridefinite qui: cosi' i due non possono disallinearsi.
    val poiTagKeys = args[2].split(",")
    val inputFiles = args.drop(3).map(::File)

    // Un bbox nazionale grande (es. Stati Uniti) supera la capacita' di una singola query
    // Overpass (visto: 504 Gateway Timeout anche a 900s) - build-region.sh lo spezza in piu'
    // chunk 5x5 gradi, ciascuno con il proprio file XML. I nodi vengono dedotti di nuovo qui
    // (oltre al dedup gia' fatto da parseOsmXml per file) perche' un nodo esattamente sul
    // confine tra due chunk puo' comparire nella risposta di entrambi.
    val nodes = inputFiles.flatMap { parseOsmXml(it).nodes }.distinctBy { it.id }
    val pois = extractPois(OsmData(nodes, emptyList()), poiTagKeys)

    writePoiDb(pois, regionId, outputDb)
    println("poi: ${pois.size} POI scritti in ${outputDb.path}")
}

data class Poi(val name: String, val category: String, val lat: Double, val lon: Double, val osmTag: String, val phone: String?)

fun extractPois(data: OsmData, poiTagKeys: List<String>): List<Poi> = data.nodes.mapNotNull { node ->
    val tagKey = poiTagKeys.firstOrNull { node.tags.containsKey(it) } ?: return@mapNotNull null
    val tagValue = node.tags.getValue(tagKey)
    Poi(
        name = node.tags["name"] ?: tagValue,
        category = tagValue,
        lat = node.lat,
        lon = node.lon,
        osmTag = "$tagKey=$tagValue",
        // "phone" e' il tag storico, "contact:phone" quello piu' recente dello schema
        // contact:* — OSM non li ha mai consolidati in uno solo, entrambi ancora in uso.
        phone = node.tags["phone"] ?: node.tags["contact:phone"],
    )
}

/**
 * Schema minimo (non lo schema Room di PoiEntity): una tabella "poi" con le stesse colonne
 * meno l'id autogenerato, che l'app importa riga per riga in region.db via PoiDao.insertAll().
 *
 * outputDb e' poi.db, il pacchetto POI della regione, scaricato e aggiornato dall'app
 * separatamente da guide (guides.db), mappa e routing.
 */
fun writePoiDb(pois: List<Poi>, regionId: String, outputDb: File) {
    writeSqliteTable(
        outputDb = outputDb,
        tableName = "poi",
        createTableSql = """
            CREATE TABLE poi (
                regionId TEXT NOT NULL,
                name TEXT NOT NULL,
                category TEXT NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                osmTag TEXT NOT NULL,
                phone TEXT
            )
            """.trimIndent(),
        insertSql = "INSERT INTO poi (regionId, name, category, lat, lon, osmTag, phone) VALUES (?, ?, ?, ?, ?, ?, ?)",
        rows = pois,
    ) { insert, poi ->
        insert.setString(1, regionId)
        insert.setString(2, poi.name)
        insert.setString(3, poi.category)
        insert.setDouble(4, poi.lat)
        insert.setDouble(5, poi.lon)
        insert.setString(6, poi.osmTag)
        insert.setString(7, poi.phone)
    }
}
