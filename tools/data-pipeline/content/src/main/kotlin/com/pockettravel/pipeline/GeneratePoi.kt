package com.pockettravel.pipeline

import com.pockettravel.core.poi.PoiPackage
import com.pockettravel.core.poi.poiPackageOf
import java.io.File
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

fun main(args: Array<String>) {
    require(args.size >= 5) {
        "Uso: generatePoi <regionId> <output poi.db> <output poi-extra.db> <poiTagKeys separate da virgola> <input1.osm.xml> [input2.osm.xml ...]"
    }
    val regionId = args[0]
    val outputDb = File(args[1])
    val extraDb = File(args[2])
    // Passate da build-region.sh (unica fonte di verita', usata anche per costruire la query
    // Overpass) invece di essere ridefinite qui: cosi' i due non possono disallinearsi.
    val poiTagKeys = args[3].split(",")
    val inputFiles = args.drop(4).map(::File)

    // Un bbox nazionale grande (es. Stati Uniti) supera la capacita' di una singola query
    // Overpass (visto: 504 Gateway Timeout anche a 900s) - build-region.sh lo spezza in piu'
    // chunk 5x5 gradi, ciascuno con il proprio file XML, letti uno dopo l'altro da readPois.
    val pois = readPois(inputFiles, poiTagKeys)

    // Base: i POI che la mappa mostra. Extra: quelli che l'utente puo' scaricare a parte. Gli altri
    // (panchine, cestini...) la mappa non li mostrerebbe comunque e non si pubblicano.
    val byPackage = pois.groupBy { poiPackageOf(it.name, it.category, it.osmTag) }
    val base = byPackage[PoiPackage.BASE].orEmpty()
    val extra = byPackage[PoiPackage.EXTRA].orEmpty()
    writePoiDb(base, regionId, outputDb)
    // Niente file extra se non c'e' nessun POI: la regione resta senza pacchetto extra.
    extraDb.delete()
    if (extra.isNotEmpty()) writePoiDb(extra, regionId, extraDb)
    println("poi: ${base.size} base in ${outputDb.path}, ${extra.size} extra, ${byPackage[null].orEmpty().size} non pubblicati")
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
    // Nodi, way e relazioni hanno spazi di id separati in OSM: la chiave li distingue.
    val seenIds = HashSet<String>()
    var tags = HashMap<String, String>()
    var id = 0L
    var lat = 0.0
    var lon = 0.0
    var inElement: String? = null
    val handler = object : DefaultHandler() {
        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            when (qName) {
                "node", "way", "relation" -> {
                    inElement = qName
                    id = attributes.getValue("id").toLong()
                    // Way e relazioni non hanno coordinate proprie: arrivano dal loro <center> ("out center").
                    lat = attributes.getValue("lat")?.toDouble() ?: Double.NaN
                    lon = attributes.getValue("lon")?.toDouble() ?: Double.NaN
                    tags = HashMap()
                }
                "center" -> if (inElement == "way" || inElement == "relation") {
                    lat = attributes.getValue("lat").toDouble()
                    lon = attributes.getValue("lon").toDouble()
                }
                "tag" -> if (inElement != null) tags[attributes.getValue("k")] = attributes.getValue("v")
            }
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            if (qName != "node" && qName != "way" && qName != "relation") return
            inElement = null
            if (lat.isNaN() || lon.isNaN()) return
            val poi = poiFrom(tags, lat, lon, poiTagKeys) ?: return
            if (seenIds.add("$qName/$id")) pois += poi
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
    // Parchi senza nome: per lo piu' aiuole e giardinetti, sulla mappa sarebbero solo "park".
    if (tagKey == "leisure" && tagValue == "park" && tags["name"] == null) return null
    return Poi(
        name = tags["name"] ?: tagValue,
        category = when {
            tagKey == "amenity" && tagValue == "parking" && tags["access"] in PRIVATE_ACCESS -> "parking_private"
            tagKey == "railway" && (tags["station"] == "subway" || tags["subway"] == "yes") -> "subway_station"
            // Uffici e centri informazioni, non i cartelli e i segnavia (stesso tag tourism=information).
            tagKey == "tourism" && tagValue == "information" && tags["information"] in INFO_OFFICE ->
                "information_office"
            else -> tagValue
        },
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
 * outputDb e' poi.db (o poi-extra.db, stesso formato), un pacchetto POI della regione, scaricato
 * e aggiornato dall'app separatamente da guide (guides.db), mappa e routing.
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

// Parcheggi non aperti a tutti (tag access OSM): l'app li mostra con un segnalino a parte. Resta
// osmTag "amenity=parking", cambia solo category.
private val PRIVATE_ACCESS = setOf("private", "customers", "no", "permit", "residents")

private val INFO_OFFICE = setOf("office", "visitor_centre")
