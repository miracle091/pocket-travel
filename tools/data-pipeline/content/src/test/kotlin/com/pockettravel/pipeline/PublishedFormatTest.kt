package com.pockettravel.pipeline

import java.io.File
import java.io.RandomAccessFile
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Istantanea del formato dei dati pubblicati, confrontata con content/src/test/resources/published-format.txt:
 * schema e marcatori di formato dei database (poi.db, guides.db, cities.db, addresses-search.db, transit.db),
 * compressione delle tile di addresses.pmtiles, forma dei JSON del catalogo (manifest.json, address-grid.json),
 * costanti di versione e min-app-version-code.
 *
 * Se l'istantanea cambia, l'app gia' rilasciata potrebbe non saper leggere i dati nuovi: il test fallisce
 * finche' qualcuno non decide se alzare min-app-version-code e aggiorna l'istantanea. Restano fuori la
 * compressione xz dei file e i campi che gli script build-*.sh aggiungono con jq (fileXz, conteggi).
 */
class PublishedFormatTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // I test girano in tools/data-pipeline (workingDir in build.gradle.kts).
    private val snapshot = File("content/src/test/resources/published-format.txt")
    private val minAppVersionCode = File("min-app-version-code").readText().trim()

    @Test
    fun `il formato dei dati pubblicati corrisponde all'istantanea salvata`() {
        val actual = formatLines().joinToString("\n", postfix = "\n")
        if (System.getenv("UPDATE_PUBLISHED_FORMAT") == "1") {
            snapshot.parentFile.mkdirs()
            snapshot.writeText(actual)
            return
        }
        val expected = if (snapshot.exists()) snapshot.readText() else ""
        if (expected != actual) fail(differenceMessage(expected.lines(), actual.lines()))
    }

    private fun differenceMessage(expected: List<String>, actual: List<String>): String {
        val savedMinApp = expected.firstOrNull { it.startsWith(MIN_APP_PREFIX) }?.removePrefix(MIN_APP_PREFIX)
        return buildString {
            appendLine("Il formato dei dati pubblicati e' cambiato rispetto a tools/data-pipeline/${snapshot.invariantSeparatorsPath}:")
            (expected - actual.toSet()).filter { it.isNotEmpty() }.forEach { appendLine("  - $it") }
            (actual - expected.toSet()).filter { it.isNotEmpty() }.forEach { appendLine("  + $it") }
            appendLine()
            appendLine("L'app rilasciata legge ancora i dati nel formato nuovo? Una tabella, una colonna o un campo JSON in piu'")
            appendLine("di solito si', a meno che l'app legga le colonne per posizione; una colonna tolta o rinominata, un tipo")
            appendLine("diverso, un'altra compressione o un altro marcatore di formato di solito no.")
            appendLine("Se non li legge: alza tools/data-pipeline/min-app-version-code al versionCode della prima app che li legge")
            appendLine("(ora $minAppVersionCode, nell'istantanea ${savedMinApp ?: "assente"}) e scrivi la voce in \"Cambiamenti incompatibili\" del CHANGELOG.")
            append("Poi aggiorna l'istantanea: UPDATE_PUBLISHED_FORMAT=1 ./gradlew :tools:data-pipeline:content:test --tests '*PublishedFormatTest' --rerun")
        }
    }

    private fun formatLines(): List<String> = listOf(
        "$MIN_APP_PREFIX$minAppVersionCode",
        "const MANIFEST_VERSION $MANIFEST_VERSION",
        "const POI_DB_FORMAT_VERSION $POI_DB_FORMAT_VERSION",
    ) + sqliteLines("poi.db") { writePoiDb(emptyList(), it) } +
        sqliteLines("guides.db") {
            writeGuidesDb(emptyList(), it)
            writeDiplomaticMissionsTable(emptyList(), it)
        } +
        sqliteLines("cities.db") { writeCitiesDb(emptyList(), it) } +
        sqliteLines("addresses-search.db") { writeAddressSearchDb(emptyList(), it) } +
        // Con gli id GTFS (reti con orari in tempo reale): lo schema piu' ampio che si pubblica.
        sqliteLines("transit.db") { generateTransit(gtfsFeed(), it, "feed", LocalDate.parse("2026-10-05"), 7, gtfsIds = true) } +
        addressesPmtilesLines() +
        jsonLines("manifest.json", JSONObject(manifestJson())) +
        jsonLines("address-grid.json", JSONObject(addressGridJson()))

    // Tabelle "meta" con chiave "format": il valore e' un marcatore di formato letto dall'app.
    private fun sqliteLines(name: String, write: (File) -> Unit): List<String> {
        val db = tmp.root.resolve(name)
        write(db)
        return DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
            val lines = mutableListOf("$name user_version ${conn.query("PRAGMA user_version").single()[0]}")
            for ((type, objectName, sql) in conn.query("SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY type, name")) {
                if (type == "table") {
                    conn.query("PRAGMA table_info(\"$objectName\")").forEach { (cid, column, columnType, notNull, default, pk) ->
                        lines += "$name $objectName.$column #$cid $columnType notnull=$notNull default=$default pk=$pk"
                    }
                    if (sql.toString().contains("WITHOUT ROWID", ignoreCase = true)) lines += "$name $objectName WITHOUT ROWID"
                    if (objectName == "meta") {
                        conn.query("SELECT value FROM meta WHERE key = 'format'").forEach { lines += "$name meta format ${it[0]}" }
                    }
                } else {
                    lines += "$name $type ${sql.toString().replace(Regex("\\s+"), " ")}"
                }
            }
            lines
        }
    }

    // Header PMTiles v3: byte 7 versione, 97 compressione interna, 98 compressione delle tile, 99 tipo di tile.
    private fun addressesPmtilesLines(): List<String> {
        val file = tmp.root.resolve("addresses.pmtiles")
        writeAddressesPmtiles(listOf(Address(43_942_400, 12_457_800, "1", "Via Roma", "Serravalle")), file, 12.44, 43.93, 12.46, 43.94)
        val header = ByteArray(PMTILES_HEADER_BYTES)
        RandomAccessFile(file, "r").use { it.readFully(header) }
        return listOf(
            "addresses.pmtiles version ${header[7]}",
            "addresses.pmtiles internal_compression ${header[97]}",
            "addresses.pmtiles tile_compression ${header[98]}",
            "addresses.pmtiles tile_type ${header[99]}",
        )
    }

    // Percorso e tipo di ogni valore; gli elementi degli array condividono il percorso "[]".
    private fun jsonLines(name: String, root: Any): List<String> {
        val lines = sortedSetOf<String>()
        fun walk(path: String, value: Any) {
            when (value) {
                is JSONObject -> {
                    lines += "$name $path object"
                    value.keys().forEach { walk("$path.$it", value.get(it)) }
                }
                is JSONArray -> {
                    lines += "$name $path array"
                    (0 until value.length()).forEach { walk("$path[]", value.get(it)) }
                }
                is String -> lines += "$name $path string"
                is Boolean -> lines += "$name $path boolean"
                is Int, is Long -> lines += "$name $path integer"
                is Number -> lines += "$name $path number"
                else -> lines += "$name $path null"
            }
        }
        walk("$", root)
        return lines.toList()
    }

    // Ogni parametro facoltativo valorizzato, perche' compaiano tutti i campi che il codice Kotlin scrive.
    private fun manifestJson(): String {
        val file = { name: String -> ManifestFileEntry(name, "https://example.org/$name", 1, "a".repeat(64), "chiave") }
        val region = buildRegionFragmentJson(
            "san-marino", "San Marino", "1", "2026-09-14T00:00:00Z",
            MapSourceInput("https://build.protomaps.com/20260914.pmtiles", 12.4, 43.89, 12.52, 43.99, 0, 14),
            routingFiles = listOf(file("E10_N40.rd5")),
            poiFile = file("poi.db"),
            poiExtraFile = file("poi-extra.db"),
            previewFile = file("preview.pmtiles"),
            previewMaxZoom = 8,
        )
        val guides = buildGuidesFragmentJson("1", file("guides.db"), mapOf("san-marino" to "https://it.wikivoyage.org/wiki/San_Marino"))
        return mergeManifestJson(
            listOf(region, guides),
            continents = mapOf("san-marino" to "Europa"),
            countryCodes = mapOf("san-marino" to "sm"),
            groups = mapOf("san-marino" to ("italia" to "Italia")),
            mapSourceUrl = "https://build.protomaps.com/20261001.pmtiles",
            minAppVersionCode = minAppVersionCode.toInt(),
            groupLabelsEn = mapOf("italia" to "Italy"),
        )
    }

    private fun addressGridJson(): String {
        val cell = """
            { "id": "11/1/1", "version": "1",
              "file": { "name": "cell.pmtiles", "url": "https://example.org/cell.pmtiles", "sizeBytes": 100, "sha256": "${"a".repeat(64)}" } }
        """.trimIndent()
        return mergeAddressGridJson("1", null, listOf(cell), listOf(GridAttribution("OpenStreetMap", "ODbL-1.0", "https://www.openstreetmap.org/copyright")), 1)
    }

    private fun gtfsFeed(): File = tmp.root.resolve("feed.zip").also { zip ->
        val files = mapOf(
            "agency.txt" to "agency_id,agency_name,agency_url,agency_timezone\na,Rete,https://example.invalid,Europe/Rome",
            "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\nA,Piazza,43.9,12.4\nB,Stazione,43.91,12.41",
            "routes.txt" to "route_id,route_short_name,route_long_name,route_type\nR1,1,Piazza - Stazione,3",
            "trips.txt" to "route_id,service_id,trip_id,trip_headsign\nR1,S,T1,Stazione",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nT1,08:00:00,08:00:00,A,1\nT1,08:10:00,08:10:00,B,2",
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nS,1,1,1,1,1,1,1,20260101,20261231",
        )
        ZipOutputStream(zip.outputStream()).use { out ->
            files.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
    }

    private fun Connection.query(sql: String): List<List<Any?>> = createStatement().use { statement ->
        statement.executeQuery(sql).use { rs ->
            val columns = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..columns).map(rs::getObject)) }
        }
    }

    private companion object {
        const val MIN_APP_PREFIX = "min-app-version-code "
        const val PMTILES_HEADER_BYTES = 127
    }
}

private operator fun <T> List<T>.component6(): T = this[5]
