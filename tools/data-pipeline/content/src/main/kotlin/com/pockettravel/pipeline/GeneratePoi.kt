package com.pockettravel.pipeline

import com.pockettravel.core.poi.poiHasContacts
import com.pockettravel.core.poi.PoiPackage
import com.pockettravel.core.poi.poiHasDetails
import com.pockettravel.core.poi.poiPackageOf
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.sql.DriverManager
import javax.xml.parsers.SAXParserFactory
import kotlin.math.roundToInt

fun main(args: Array<String>) {
    require(args.size >= 5) {
        "Uso: generatePoi <regionId> <output poi.db> <output poi-extra.db> <poiTagKeys separate da virgola> <input1.osm.xml> [input2.osm.xml ...]"
    }
    // regionId non finisce piu' nel file (era una colonna costante su ogni riga, vedi writePoiDb):
    // resta come argomento posizionale per compatibilita' con build-region.sh, non altrimenti usato qui.
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
    writePoiDb(base, outputDb)
    // Niente file extra se non c'e' nessun POI: la regione resta senza pacchetto extra.
    extraDb.delete()
    if (extra.isNotEmpty()) writePoiDb(extra, extraDb)
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

data class Poi(
    val name: String,
    val category: String,
    val lat: Double,
    val lon: Double,
    val osmTag: String,
    val phone: String?,
    // Tag OSM "wheelchair" (yes, limited, no, designated...), null se assente: per la modalita' Accessibilita'.
    val wheelchair: String? = null,
    // Solo per i POI con poiHasDetails (cibo, alloggi, ambasciate, farmacie, ospedali, negozi), null se OSM non li indica.
    val openingHours: String? = null,
    val address: String? = null,
    // Solo per i POI con poiHasContacts (alloggi, ambasciate e consolati).
    val website: String? = null,
    val email: String? = null,
    // Ambasciate e consolati: paese che rappresentano (tag OSM "country", ISO 3166-1 alpha-2), per
    // mostrare a chi viaggia quelle del proprio paese.
    val country: String? = null,
)

private fun poiFrom(tags: Map<String, String>, lat: Double, lon: Double, poiTagKeys: List<String>): Poi? {
    val tagKey = poiTagKeys.firstOrNull { tags.containsKey(it) } ?: return null
    val tagValue = tags.getValue(tagKey)
    // Parchi senza nome: per lo piu' aiuole e giardinetti, sulla mappa sarebbero solo "park".
    if (tagKey == "leisure" && tagValue == "park" && tags["name"] == null) return null
    val category = when {
        tagKey == "amenity" && tagValue == "parking" && tags["access"] in PRIVATE_ACCESS -> "parking_private"
        tagKey == "railway" && (tags["station"] == "subway" || tags["subway"] == "yes") -> "subway_station"
        // Uffici e centri informazioni, non i cartelli e i segnavia (stesso tag tourism=information).
        tagKey == "tourism" && tagValue == "information" && tags["information"] in INFO_OFFICE ->
            "information_office"
        // Tag moderno delle rappresentanze (ambasciate, consolati...), al posto di amenity=embassy.
        tagKey == "office" && tagValue == "diplomatic" -> "embassy"
        else -> tagValue
    }
    val osmTag = "$tagKey=$tagValue"
    val details = poiHasDetails(category, osmTag)
    val contacts = poiHasContacts(category, osmTag)
    return Poi(
        // Farmacie, supermercati e catene spesso hanno solo il marchio ("Conad", "Lloyds"): meglio del tipo.
        name = tags["name"] ?: tags["brand"]?.takeIf { details } ?: tagValue,
        category = category,
        lat = lat,
        lon = lon,
        osmTag = osmTag,
        // "phone" e' il tag storico, "contact:phone" quello piu' recente dello schema
        // contact:* — OSM non li ha mai consolidati in uno solo, entrambi ancora in uso.
        phone = tags["phone"] ?: tags["contact:phone"],
        wheelchair = tags["wheelchair"],
        openingHours = tags["opening_hours"].takeIf { details },
        address = addressOf(tags).takeIf { details },
        // Come per phone: schema storico e contact:*, entrambi in uso.
        website = (tags["website"] ?: tags["contact:website"]).takeIf { contacts },
        email = (tags["email"] ?: tags["contact:email"]).takeIf { contacts },
        country = if (category == "embassy") representedCountry(tags["country"]) else null,
    )
}

// "IT", anche da "it" o "IT;SM" (piu' paesi: il primo). null se non e' un codice a due lettere.
private fun representedCountry(raw: String?): String? =
    raw?.substringBefore(';')?.trim()?.uppercase()?.takeIf { it.matches(Regex("[A-Z]{2}")) }

// "Via Roma 12, Rimini": via (o localita' senza via) e civico, poi la citta'. Senza via niente
// indirizzo: la sola citta' non aiuta a trovare il posto.
private fun addressOf(tags: Map<String, String>): String? {
    val street = tags["addr:street"] ?: tags["addr:place"] ?: return null
    val line = listOfNotNull(street, tags["addr:housenumber"]).joinToString(" ")
    return listOfNotNull(line, tags["addr:city"]).joinToString(", ")
}

/**
 * Formato compatto (non lo schema Room di PoiEntity), letto riga per riga dall'app via
 * PoiDao.insertAll() dopo il parsing di PoiImporter (core:sync):
 * - "poi_code": una riga per ogni coppia (category, osmTag) distinta della regione (poche decine
 *   o centinaia anche su un file con decine di migliaia di POI), cosi' "poi" non ripete due
 *   stringhe identiche a ogni riga ma un solo intero.
 * - "poi": name, il code di poi_code, le coordinate come interi in microgradi (lat/lon * 1e6,
 *   precisione ~0,11 m, piu' che sufficiente per un segnalino) invece di REAL a 8 byte, phone e
 *   wheelchair facoltativi, openingHours e address per cibo, alloggi, ambasciate, farmacie, ospedali e negozi, website ed email per alloggi e ambasciate, country (paese rappresentato) per le ambasciate (colonne
 *   aggiunte dopo: le versioni dell'app che non le conoscono non le selezionano). Niente colonna
 *   regionId (era costante su ogni riga: la regione la passa comunque chi importa il file).
 * - PRAGMA user_version = [POI_DB_FORMAT_VERSION]: marcatore di formato per PoiImporter, che
 *   legge sia questo che il vecchio formato (regionId/category/osmTag/lat/lon in chiaro,
 *   user_version assente cioe' 0 di default) - vedi PoiImporter.readPois.
 *
 * outputDb e' poi.db (o poi-extra.db, stesso formato), un pacchetto POI della regione, scaricato
 * e aggiornato dall'app separatamente da guide (guides.db), mappa e routing.
 */
fun writePoiDb(pois: List<Poi>, outputDb: File) {
    // LinkedHashMap: assegna i code in ordine di prima comparsa, solo per avere un file
    // deterministico a parita' di input (non serve altrimenti).
    val codeOf = LinkedHashMap<Pair<String, String>, Int>()
    for (poi in pois) codeOf.getOrPut(poi.category to poi.osmTag) { codeOf.size }

    writeSqliteTable(
        outputDb = outputDb,
        tableName = "poi_code",
        createTableSql = """
            CREATE TABLE poi_code (
                code INTEGER NOT NULL PRIMARY KEY,
                category TEXT NOT NULL,
                osmTag TEXT NOT NULL
            )
            """.trimIndent(),
        insertSql = "INSERT INTO poi_code (code, category, osmTag) VALUES (?, ?, ?)",
        rows = codeOf.entries.toList(),
    ) { insert, entry ->
        insert.setInt(1, entry.value)
        insert.setString(2, entry.key.first)
        insert.setString(3, entry.key.second)
    }

    writeSqliteTable(
        outputDb = outputDb,
        tableName = "poi",
        createTableSql = """
            CREATE TABLE poi (
                name TEXT NOT NULL,
                code INTEGER NOT NULL,
                latE6 INTEGER NOT NULL,
                lonE6 INTEGER NOT NULL,
                phone TEXT,
                wheelchair TEXT,
                openingHours TEXT,
                address TEXT,
                website TEXT,
                email TEXT,
                country TEXT
            )
            """.trimIndent(),
        insertSql = "INSERT INTO poi (name, code, latE6, lonE6, phone, wheelchair, openingHours, address, website, email, country) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        rows = pois,
    ) { insert, poi ->
        insert.setString(1, poi.name)
        insert.setInt(2, codeOf.getValue(poi.category to poi.osmTag))
        insert.setInt(3, (poi.lat * 1_000_000.0).roundToInt())
        insert.setInt(4, (poi.lon * 1_000_000.0).roundToInt())
        insert.setString(5, poi.phone)
        insert.setString(6, poi.wheelchair)
        insert.setString(7, poi.openingHours)
        insert.setString(8, poi.address)
        insert.setString(9, poi.website)
        insert.setString(10, poi.email)
        insert.setString(11, poi.country)
    }

    // A parte (non e' una tabella): writeSqliteTable ricrea una tabella per volta, il marcatore di
    // formato riguarda il file intero.
    DriverManager.getConnection("jdbc:sqlite:${outputDb.path}").use { conn ->
        conn.createStatement().use { it.execute("PRAGMA user_version = $POI_DB_FORMAT_VERSION") }
    }
}

/** Formato compatto (poi_code + microgradi), vedi [writePoiDb]. 0 (assente) e' il vecchio formato. */
const val POI_DB_FORMAT_VERSION = 1

// Parcheggi non aperti a tutti (tag access OSM): l'app li mostra con un segnalino a parte. Resta
// osmTag "amenity=parking", cambia solo category.
private val PRIVATE_ACCESS = setOf("private", "customers", "no", "permit", "residents")

private val INFO_OFFICE = setOf("office", "visitor_centre")
