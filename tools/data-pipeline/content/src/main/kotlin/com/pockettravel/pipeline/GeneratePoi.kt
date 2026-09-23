package com.pockettravel.pipeline

import java.io.File
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

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
    // chunk 5x5 gradi, ciascuno con il proprio file XML, letti uno dopo l'altro da readPois.
    val pois = readPois(inputFiles, poiTagKeys)

    writePoiDb(pois, regionId, outputDb)
    println("poi: ${pois.size} POI scritti in ${outputDb.path}")
}

/**
 * Legge i POI dagli XML Overpass in streaming, tenendo in memoria solo i [Poi] (pochi campi) e
 * non ogni nodo con tutti i suoi tag come parseOsmXml: con tutti i nodi della Germania in
 * memoria generatePoi esauriva lo heap di 4g (OutOfMemoryError nel build del 2026-09-23).
 * Un nodo esattamente sul confine tra due chunk puo' comparire in entrambi i file: conta una
 * volta sola.
 */
fun readPois(files: List<File>, poiTagKeys: List<String>): List<Poi> {
    // Stessi limiti JAXP disattivati di parseOsmXml (maptiles): innocui con disallow-doctype-decl,
    // scattano su estratti Overpass nazionali di milioni di righe.
    System.setProperty("jdk.xml.maxGeneralEntitySizeLimit", "0")
    System.setProperty("jdk.xml.totalEntitySizeLimit", "0")
    System.setProperty("jdk.xml.entityExpansionLimit", "0")

    val pois = mutableListOf<Poi>()
    val seenIds = HashSet<Long>()
    var tags = HashMap<String, String>()
    var id = 0L
    var lat = 0.0
    var lon = 0.0
    var inNode = false
    val handler = object : DefaultHandler() {
        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            when (qName) {
                "node" -> {
                    inNode = true
                    id = attributes.getValue("id").toLong()
                    lat = attributes.getValue("lat").toDouble()
                    lon = attributes.getValue("lon").toDouble()
                    tags = HashMap()
                }
                "tag" -> if (inNode) tags[attributes.getValue("k")] = attributes.getValue("v")
            }
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            if (qName != "node") return
            inNode = false
            val poi = poiFrom(tags, lat, lon, poiTagKeys) ?: return
            if (seenIds.add(id)) pois += poi
        }
    }
    val parser = SAXParserFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        isXIncludeAware = false
    }.newSAXParser()
    files.forEach { parser.parse(it, handler) }
    return pois
}

data class Poi(val name: String, val category: String, val lat: Double, val lon: Double, val osmTag: String, val phone: String?)

private fun poiFrom(tags: Map<String, String>, lat: Double, lon: Double, poiTagKeys: List<String>): Poi? {
    val tagKey = poiTagKeys.firstOrNull { tags.containsKey(it) } ?: return null
    val tagValue = tags.getValue(tagKey)
    return Poi(
        name = tags["name"] ?: tagValue,
        category = tagValue,
        lat = lat,
        lon = lon,
        osmTag = "$tagKey=$tagValue",
        // "phone" e' il tag storico, "contact:phone" quello piu' recente dello schema
        // contact:* — OSM non li ha mai consolidati in uno solo, entrambi ancora in uso.
        phone = tags["phone"] ?: tags["contact:phone"],
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
