package com.pockettravel.core.data.db

import androidx.room.migration.Migration
import com.pockettravel.core.data.POI_TO_REDOWNLOAD_VERSION
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Le migrazioni eseguite davvero su sqlite-jdbc: il database di partenza si costruisce dallo schema Room esportato di quella
 * versione (core/data/schemas), poi si applicano le migrazioni e si confronta con lo schema esportato della versione finale e
 * con i dati attesi. Complementa il test strumentato delle migrazioni sul dispositivo.
 */
class MigrationsSqlTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var connection: Connection

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After
    fun tearDown() = connection.close()

    // ---- schemi esportati ----

    private fun schemaFile(version: Int): File {
        val directory = listOf("schemas", "core/data/schemas")
            .map { File(it, "com.pockettravel.core.data.db.RegionDatabase") }
            .firstOrNull { it.isDirectory }
        requireNotNull(directory) { "cartella degli schemi Room non trovata da ${File(".").absolutePath}" }
        return File(directory, "$version.json")
    }

    private fun entitiesOf(version: Int): JsonArray =
        Json.parseToJsonElement(schemaFile(version).readText()).jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray

    /** Crea nella connessione il database come lo avrebbe Room alla [version]: tabelle, indici e trigger delle tabelle FTS. */
    private fun createSchema(version: Int, target: Connection = connection) {
        for (entity in entitiesOf(version).map { it.jsonObject }) {
            val table = entity.getValue("tableName").jsonPrimitive.content
            fun sql(text: String) = text.replace("\${TABLE_NAME}", table)
            target.createStatement().use { statement ->
                statement.execute(sql(entity.getValue("createSql").jsonPrimitive.content))
                (entity["indices"] as? JsonArray).orEmpty().forEach { statement.execute(sql(it.jsonObject.getValue("createSql").jsonPrimitive.content)) }
                (entity["contentSyncTriggers"] as? JsonArray).orEmpty().forEach { statement.execute(it.jsonPrimitive.content) }
            }
        }
    }

    private fun migrate(from: Int, to: Int, target: Connection = connection) {
        val db = jdbcSupportDatabase(target)
        var version = from
        while (version < to) {
            val step: Migration = ALL_MIGRATIONS.single { it.startVersion == version }
            step.migrate(db)
            version = step.endVersion
        }
        assertEquals("la catena deve arrivare esattamente a $to", to, version)
    }

    // ---- confronto di schemi ----

    private data class Shape(
        val tables: Map<String, Set<String>>,
        val indexes: Map<String, String>,
        val triggers: Set<String>,
    )

    private fun shapeOf(target: Connection): Shape {
        val shadow = Regex("_(content|segments|segdir|docsize|stat)$")
        val tables = mutableMapOf<String, Set<String>>()
        val names = rows(target, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").map { it[0] as String }
        for (name in names.filterNot { shadow.containsMatchIn(it) }) {
            tables[name] = rows(target, "PRAGMA table_info(`$name`)").map { "${it[1]}|${it[2]}|${it[3]}|${it[4]}|${it[5]}" }.toSet()
        }
        val indexes = mutableMapOf<String, String>()
        for (table in tables.keys) {
            // Le tabelle virtuali FTS non hanno indici propri.
            for (index in rows(target, "PRAGMA index_list(`$table`)")) {
                val name = index[1] as String
                if (name.startsWith("sqlite_autoindex")) continue
                val columns = rows(target, "PRAGMA index_info(`$name`)").joinToString(",") { it[2] as String }
                indexes[name] = "$table($columns) unique=${index[2]}"
            }
        }
        val triggers = rows(target, "SELECT name FROM sqlite_master WHERE type = 'trigger'").map { it[0] as String }.toSet()
        return Shape(tables, indexes, triggers)
    }

    private fun rows(target: Connection, sql: String, vararg args: Any?): List<List<Any?>> =
        target.prepareStatement(sql).use { statement ->
            args.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
            statement.executeQuery().use { rs ->
                val count = rs.metaData.columnCount
                buildList { while (rs.next()) add((1..count).map { rs.getObject(it) }) }
            }
        }

    private fun rows(sql: String, vararg args: Any?) = rows(connection, sql, *args)

    private fun scalar(sql: String, vararg args: Any?): Any? = rows(sql, *args).single().single()

    private fun insert(table: String, values: Map<String, Any?>, target: Connection = connection) {
        val columns = values.keys.joinToString(", ") { "`$it`" }
        val marks = values.keys.joinToString(", ") { "?" }
        target.prepareStatement("INSERT INTO `$table` ($columns) VALUES ($marks)").use { statement ->
            values.values.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
            statement.executeUpdate()
        }
    }

    private fun columnsOf(table: String): List<String> = rows("PRAGMA table_info(`$table`)").map { it[1] as String }

    // ---- catena completa ----

    @Test
    fun `da ogni versione pubblicata alla corrente lo schema migrato e' quello esportato`() {
        val current = ALL_MIGRATIONS.maxOf { it.endVersion }
        val expected = DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
            createSchema(current, fresh)
            shapeOf(fresh)
        }
        for (start in ALL_MIGRATIONS.map { it.startVersion }) {
            DriverManager.getConnection("jdbc:sqlite::memory:").use { migrated ->
                createSchema(start, migrated)
                migrate(start, current, migrated)
                val actual = shapeOf(migrated)
                assertEquals("tabelle da $start a $current", expected.tables.keys, actual.tables.keys)
                for ((table, columns) in expected.tables) assertEquals("colonne di $table da $start", columns, actual.tables.getValue(table))
                assertEquals("indici da $start", expected.indexes, actual.indexes)
                assertEquals("trigger da $start", expected.triggers, actual.triggers)
            }
        }
    }

    // ---- migrazioni singole ----

    @Test
    fun `3 a 5 aggiunge il telefono ai POI esistenti e crea i numeri di emergenza`() {
        createSchema(3)
        insert("poi", mapOf("regionId" to "italia", "name" to "Ambasciata", "category" to "embassy", "lat" to 41.9, "lon" to 12.5, "osmTag" to "amenity=embassy"))
        migrate(3, 5)

        assertTrue("phone" in columnsOf("poi"))
        assertNull(scalar("SELECT phone FROM poi"))
        assertEquals("Ambasciata", scalar("SELECT name FROM poi"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM emergency_numbers").let { (it as Number).toInt() })
    }

    @Test
    fun `5 a 6 copia la versione unica nei tre pacchetti e lascia i POI senza dimensione`() {
        createSchema(5)
        insert("installed_regions", mapOf("regionId" to "italia", "displayName" to "Italia", "version" to "2026.01.01", "sizeBytes" to 500L, "installedAt" to 1L))
        insert("installed_regions", mapOf("regionId" to "perù", "displayName" to "Perù è", "version" to "", "sizeBytes" to Long.MAX_VALUE, "installedAt" to 2L))
        migrate(5, 6)

        val italy = rows("SELECT mapVersion, routingVersion, poiVersion, poiSizeBytes, sizeBytes, installedAt FROM installed_regions WHERE regionId = ?", "italia").single()
        assertEquals(listOf<Any?>("2026.01.01", "2026.01.01", "2026.01.01", null, 500, 1), italy.map { (it as? Long)?.toInt() ?: it })
        // Unicode nell'id e una dimensione al limite dei 64 bit passano intatti.
        val peru = rows("SELECT displayName, sizeBytes FROM installed_regions WHERE regionId = ?", "perù").single()
        assertEquals("Perù è", peru[0])
        assertEquals(Long.MAX_VALUE, peru[1])
        assertEquals(0, (scalar("SELECT COUNT(*) FROM installed_guides") as Number).toInt())
    }

    @Test
    fun `5 a 6 con nessuna regione installata crea comunque la tabella delle guide`() {
        createSchema(5)
        migrate(5, 6)
        assertEquals(0, (scalar("SELECT COUNT(*) FROM installed_regions") as Number).toInt())
        assertTrue("version" in columnsOf("installed_guides"))
    }

    @Test
    fun `6 a 9 aggiunge codice paese e civici vuoti`() {
        createSchema(6)
        insert(
            "installed_regions",
            mapOf("regionId" to "italia", "displayName" to "Italia", "mapVersion" to "1", "sizeBytes" to 1L, "installedAt" to 1L),
        )
        migrate(6, 9)

        val row = rows("SELECT countryCode, addressesVersion, mapVersion FROM installed_regions").single()
        assertEquals(listOf<Any?>(null, null, "1"), row)
        assertTrue(columnsOf("emergency_numbers_none").contains("regionId"))
    }

    @Test
    fun `9 a 11 mette i POI esistenti nel pacchetto base e senza accessibilita'`() {
        createSchema(9)
        insert("poi", mapOf("regionId" to "italia", "name" to "Bar", "category" to "cafe", "lat" to 1.0, "lon" to 2.0, "osmTag" to "amenity=cafe"))
        migrate(9, 11)

        assertEquals(0, (scalar("SELECT extra FROM poi") as Number).toInt())
        assertNull(scalar("SELECT wheelchair FROM poi"))
        assertTrue(columnsOf("installed_regions").containsAll(listOf("poiExtraVersion", "poiExtraSizeBytes")))
    }

    @Test
    fun `11 a 15 ricostruisce l'indice di ricerca delle guide e lo tiene sincronizzato`() {
        createSchema(11)
        insert("guide_sections", mapOf("regionId" to "lettonia", "category" to "STORY", "title" to "Rīga", "body" to "La capitale sul Daugava", "sourceUrl" to "u"))
        migrate(11, 15)

        // Il dato gia' presente e' nell'indice, e unicode61 ignora maiuscole e segni diacritici.
        assertEquals(1, rows("SELECT docid FROM guide_sections_fts WHERE guide_sections_fts MATCH ?", "riga").size)
        assertEquals(1, rows("SELECT docid FROM guide_sections_fts WHERE guide_sections_fts MATCH ?", "RĪGA").size)
        assertEquals(1, rows("SELECT docid FROM guide_sections_fts WHERE guide_sections_fts MATCH ?", "daugava").size)

        // I trigger inseriscono, aggiornano ed eliminano nell'indice.
        insert("guide_sections", mapOf("regionId" to "estonia", "category" to "STORY", "title" to "Tallinn", "body" to "porto", "sourceUrl" to "u"))
        assertEquals(1, rows("SELECT docid FROM guide_sections_fts WHERE guide_sections_fts MATCH ?", "tallinn").size)
        connection.prepareStatement("UPDATE guide_sections SET title = ? WHERE regionId = ?").use {
            it.setString(1, "Tartu")
            it.setString(2, "estonia")
            it.executeUpdate()
        }
        assertEquals(0, rows("SELECT docid FROM guide_sections_fts WHERE guide_sections_fts MATCH ?", "tallinn").size)
        assertEquals(1, rows("SELECT docid FROM guide_sections_fts WHERE guide_sections_fts MATCH ?", "tartu").size)
        connection.prepareStatement("DELETE FROM guide_sections WHERE regionId = ?").use {
            it.setString(1, "estonia")
            it.executeUpdate()
        }
        assertEquals(0, rows("SELECT docid FROM guide_sections_fts WHERE guide_sections_fts MATCH ?", "tartu").size)
    }

    @Test
    fun `11 a 15 crea le citta' e le note vuote e con la ricerca funzionante`() {
        createSchema(11)
        migrate(11, 15)

        assertEquals(0, (scalar("SELECT COUNT(*) FROM notes") as Number).toInt())
        insert("city_sections", mapOf("regionId" to "italia", "city" to "Napoli", "category" to "STORY", "title" to "Spaccanapoli", "body" to "pizza", "sourceUrl" to "u"))
        assertEquals(1, rows("SELECT docid FROM city_sections_fts WHERE city_sections_fts MATCH ?", "pizza").size)
        insert("notes", mapOf("title" to "", "body" to "", "updatedAt" to 0L))
        assertEquals(1, (scalar("SELECT COUNT(*) FROM notes") as Number).toInt())
    }

    @Test
    fun `15 a 17 aggiunge solo colonne vuote e conserva le righe`() {
        createSchema(15)
        insert("installed_regions", mapOf("regionId" to "italia", "displayName" to "Italia", "sizeBytes" to 9L, "installedAt" to 3L))
        migrate(15, 17)

        assertNull(scalar("SELECT transitVersion FROM installed_regions"))
        assertEquals("Italia", scalar("SELECT displayName FROM installed_regions"))
        assertTrue(columnsOf("poi").containsAll(listOf("openingHours", "address", "website", "email", "country", "nameEn", "nameIt")))
    }

    @Test
    fun `17 a 24 da' valori di default alle colonne nuove delle sezioni gia' importate`() {
        createSchema(17)
        insert("guide_sections", mapOf("regionId" to "italia", "category" to "STORY", "title" to "t", "body" to "b", "sourceUrl" to "u"))
        insert("city_sections", mapOf("regionId" to "italia", "city" to "Roma", "category" to "STORY", "title" to "t", "body" to "b", "sourceUrl" to "u"))
        migrate(17, 24)

        assertEquals(0, (scalar("SELECT translated FROM guide_sections") as Number).toInt())
        assertEquals(0, (scalar("SELECT translated FROM city_sections") as Number).toInt())
        assertEquals(0, (scalar("SELECT capital FROM city_sections") as Number).toInt())
        assertNull(scalar("SELECT population FROM city_sections"))
        assertNull(scalar("SELECT latitude FROM city_sections"))
        assertEquals(0, (scalar("SELECT COUNT(*) FROM vacc_meta") as Number).toInt())
        assertEquals(0, (scalar("SELECT COUNT(*) FROM diplomatic_missions") as Number).toInt())
    }

    // ---- 24 -> 25: POI in forma compatta ----

    private fun region(id: String, poi: String?, extra: String?, poiBytes: Long?, extraBytes: Long?, size: Long, map: String? = null) = insert(
        "installed_regions",
        mapOf(
            "regionId" to id, "displayName" to id, "mapVersion" to map, "poiVersion" to poi, "poiExtraVersion" to extra, "poiSizeBytes" to poiBytes,
            "poiExtraSizeBytes" to extraBytes, "sizeBytes" to size, "installedAt" to 1L,
        ),
    )

    private fun poi24(
        id: Long, regionId: String, name: String, category: String, osmTag: String, lat: Double, lon: Double, extra: Int = 0,
        nameIt: String? = null, capacity: Int? = null,
    ) = insert(
        "poi",
        mapOf(
            "id" to id, "regionId" to regionId, "name" to name, "category" to category, "osmTag" to osmTag, "lat" to lat, "lon" to lon, "extra" to extra,
            "nameIt" to nameIt, "capacityDisabled" to capacity,
        ),
    )

    private fun fillV24() {
        createSchema(24)
        region("italia", "1", "1", 1000L, 400L, 5000L, map = "m")
        region("francia", "1", null, 700L, null, 900L)
        region("nepal", null, null, null, null, 77L, map = "m")
        poi24(10, "italia", "Caffè Rigā", "cafe", "amenity=cafe", 41.9, 12.5, nameIt = "Caffè")
        poi24(20, "italia", "Farmacia", "pharmacy", "amenity=pharmacy", -33.8688, -73.9857, capacity = 2)
        poi24(5, "italia", "Fontana", "drinking_water", "amenity=drinking_water", 0.0000004, 0.0000006, extra = 1)
        poi24(30, "francia", "Bar", "cafe", "amenity=cafe", 90.0, -180.0)
    }

    @Test
    fun `24 a 25 converte i POI mantenendo id campi e coordinate in microgradi`() {
        fillV24()
        migrate(24, 25)

        assertEquals(4, (scalar("SELECT COUNT(*) FROM poi") as Number).toInt())
        val converted = rows(
            "SELECT poi.id, poi_region.regionId, poi_code.category, poi_code.osmTag, poi.latE6, poi.lonE6, poi.extra, poi.name, poi.nameIt, poi.capacityDisabled " +
                "FROM poi JOIN poi_region ON poi_region.ref = poi.regionRef JOIN poi_code ON poi_code.code = poi.code ORDER BY poi.id",
        ).map { row -> row.map { (it as? Number)?.toLong() ?: it } }
        assertEquals(
            listOf(
                listOf<Any?>(5L, "italia", "drinking_water", "amenity=drinking_water", 0L, 1L, 1L, "Fontana", null, null),
                listOf<Any?>(10L, "italia", "cafe", "amenity=cafe", 41_900_000L, 12_500_000L, 0L, "Caffè Rigā", "Caffè", null),
                listOf<Any?>(20L, "italia", "pharmacy", "amenity=pharmacy", -33_868_800L, -73_985_700L, 0L, "Farmacia", null, 2L),
                listOf<Any?>(30L, "francia", "cafe", "amenity=cafe", 90_000_000L, -180_000_000L, 0L, "Bar", null, null),
            ),
            converted,
        )
        // Le coppie e le regioni sono una volta sola.
        assertEquals(3, (scalar("SELECT COUNT(*) FROM poi_code") as Number).toInt())
        assertEquals(2, (scalar("SELECT COUNT(*) FROM poi_region") as Number).toInt())
        // Le regioni senza POI (nepal) non entrano in poi_region.
        assertEquals(0, (scalar("SELECT COUNT(*) FROM poi_region WHERE regionId = ?", "nepal") as Number).toInt())
    }

    @Test
    fun `24 a 25 ricalcola il peso dei POI per regione e il totale cambia della differenza`() {
        fillV24()
        migrate(24, 25)

        fun estimate(vararg rows: com.pockettravel.core.data.db.PoiRow) = rows.sumOf { it.estimatedStorageBytes() }
        fun row(name: String, nameIt: String? = null, capacity: Int? = null, extra: Boolean = false) =
            PoiRow(regionRef = 1, code = 1, latE6 = 0, lonE6 = 0, name = name, nameIt = nameIt, capacityDisabled = capacity, extra = extra)

        val italyBase = estimate(row("Caffè Rigā", nameIt = "Caffè"), row("Farmacia", capacity = 2))
        val italyExtra = estimate(row("Fontana", extra = true))
        val franceBase = estimate(row("Bar"))
        val italy = rows("SELECT poiSizeBytes, poiExtraSizeBytes, sizeBytes FROM installed_regions WHERE regionId = ?", "italia").single().map { (it as Number).toLong() }
        assertEquals(listOf(italyBase, italyExtra, 5000L - 1000L - 400L + italyBase + italyExtra), italy)

        val france = rows("SELECT poiSizeBytes, poiExtraSizeBytes, sizeBytes FROM installed_regions WHERE regionId = ?", "francia").single()
        assertEquals(franceBase, (france[0] as Number).toLong())
        assertNull(france[1])
        assertEquals(900L - 700L + franceBase, (france[2] as Number).toLong())

        // Regione senza POI installati: nulla cambia.
        val nepal = rows("SELECT poiSizeBytes, poiExtraSizeBytes, sizeBytes FROM installed_regions WHERE regionId = ?", "nepal").single()
        assertEquals(listOf<Any?>(null, null, 77), nepal.map { (it as? Number)?.toInt() })
    }

    @Test
    fun `24 a 25 con un pacchetto POI installato ma senza righe registra peso zero`() {
        createSchema(24)
        region("islanda", "1", "1", 900L, 100L, 1000L)
        migrate(24, 25)

        val row = rows("SELECT poiSizeBytes, poiExtraSizeBytes, sizeBytes FROM installed_regions").single().map { (it as Number).toLong() }
        assertEquals(listOf(0L, 0L, 0L), row)
    }

    @Test
    fun `24 a 25 senza spazio svuota i POI e segna da riscaricare solo i pacchetti POI installati`() {
        fillV24()
        region("peru", "2", null, 5000L, null, 3000L)
        connection.createStatement().use { it.execute("INSERT INTO notes(title, body, updatedAt) VALUES ('t', 'b', 1)") }
        migratePoiToCompact(jdbcSupportDatabase(connection), freeBytes = 0)

        assertEquals(0, (scalar("SELECT COUNT(*) FROM poi") as Number).toInt())
        assertEquals(0, (scalar("SELECT COUNT(*) FROM poi_region") as Number).toInt())
        assertEquals(0, (scalar("SELECT COUNT(*) FROM poi_code") as Number).toInt())
        fun state(id: String) = rows(
            "SELECT poiVersion, poiExtraVersion, poiSizeBytes, poiExtraSizeBytes, sizeBytes, mapVersion FROM installed_regions WHERE regionId = ?", id,
        ).single().map { (it as? Number)?.toLong() ?: it }

        assertEquals(listOf<Any?>(POI_TO_REDOWNLOAD_VERSION, POI_TO_REDOWNLOAD_VERSION, 0L, 0L, 3600L, "m"), state("italia"))
        assertEquals(listOf<Any?>(POI_TO_REDOWNLOAD_VERSION, null, 0L, null, 200L, null), state("francia"))
        // Un peso registrato piu' grande del totale non rende il totale negativo.
        assertEquals(listOf<Any?>(POI_TO_REDOWNLOAD_VERSION, null, 0L, null, 0L, null), state("peru"))
        assertEquals(listOf<Any?>(null, null, null, null, 77L, "m"), state("nepal"))
        // I dati dell'utente restano.
        assertEquals(1, (scalar("SELECT COUNT(*) FROM notes") as Number).toInt())
    }

    @Test
    fun `24 a 25 lascia la tabella poi con gli indici nuovi anche senza conversione`() {
        fillV24()
        migratePoiToCompact(jdbcSupportDatabase(connection), freeBytes = 0)

        val indexes = shapeOf(connection).indexes.keys.filter { it.startsWith("index_poi_") }
        assertTrue(indexes.containsAll(listOf("index_poi_regionRef", "index_poi_regionRef_latE6", "index_poi_regionRef_code")))
        assertFalse(indexes.contains("index_poi_regionId"))
        assertTrue(columnsOf("poi").containsAll(listOf("regionRef", "code", "latE6", "lonE6")))
        assertFalse(columnsOf("poi").contains("lat"))
    }

    @Test
    fun `24 a 25 converte quando lo spazio libero e' esattamente quello richiesto e non con un byte in meno`() {
        fillV24()
        val needed = poiConversionNeedsBytes(jdbcSupportDatabase(connection))

        // La richiesta e' il doppio della stima dei POI piu' 8 MiB di margine.
        val estimate = (scalar("SELECT SUM($POI_ROW_ESTIMATE_SQL) FROM poi") as Number).toLong()
        assertEquals(2 * estimate + 8L * 1024 * 1024, needed)

        migratePoiToCompact(jdbcSupportDatabase(connection), freeBytes = needed)
        assertEquals(4, (scalar("SELECT COUNT(*) FROM poi") as Number).toInt())
    }

    @Test
    fun `24 a 25 con un byte in meno del necessario non converte`() {
        fillV24()
        val needed = poiConversionNeedsBytes(jdbcSupportDatabase(connection))
        migratePoiToCompact(jdbcSupportDatabase(connection), freeBytes = needed - 1)
        assertEquals(0, (scalar("SELECT COUNT(*) FROM poi") as Number).toInt())
    }

    @Test
    fun `24 a 25 senza POI chiede solo il margine`() {
        createSchema(24)
        assertEquals(8L * 1024 * 1024, poiConversionNeedsBytes(jdbcSupportDatabase(connection)))
        migratePoiToCompact(jdbcSupportDatabase(connection), freeBytes = 8L * 1024 * 1024)
        assertEquals(0, (scalar("SELECT COUNT(*) FROM poi") as Number).toInt())
        assertTrue(columnsOf("poi").contains("latE6"))
    }

    @Test
    fun `lo spazio libero si legge dal disco del file e senza file e' illimitato`() {
        fillV24()
        // In memoria (o senza percorso) non c'e' un disco da controllare: si converte sempre.
        migratePoiToCompact(jdbcSupportDatabase(connection, null))
        assertEquals(4, (scalar("SELECT COUNT(*) FROM poi") as Number).toInt())

        val file = folder.newFile("region.db")
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { onDisk ->
            createSchema(24, onDisk)
            insert("poi", mapOf("regionId" to "r", "name" to "n", "category" to "c", "osmTag" to "t=1", "lat" to 1.0, "lon" to 2.0), onDisk)
            // Su un disco vero c'e' spazio per un solo POI: si converte.
            migratePoiToCompact(jdbcSupportDatabase(onDisk, file.absolutePath))
            assertEquals(1, (rows(onDisk, "SELECT COUNT(*) FROM poi").single().single() as Number).toInt())
            assertEquals(1_000_000L, (rows(onDisk, "SELECT latE6 FROM poi").single().single() as Number).toLong())
        }
    }

    @Test
    fun `24 a 25 con molti POI e testi Unicode conserva ogni riga`() {
        createSchema(24)
        region("giappone", "1", null, 10L, null, 10L)
        connection.autoCommit = false
        repeat(2_000) { i ->
            poi24(i + 1L, "giappone", "東京駅 $i", "station", "railway=station", 35.0 + i * 0.0001, 139.0 + i * 0.0001)
        }
        connection.commit()
        connection.autoCommit = true
        migrate(24, 25)

        assertEquals(2_000, (scalar("SELECT COUNT(*) FROM poi") as Number).toInt())
        assertEquals(1, (scalar("SELECT COUNT(*) FROM poi_code") as Number).toInt())
        assertEquals("東京駅 1999", scalar("SELECT name FROM poi WHERE id = 2000"))
        // 35 + 1999 * 0.0001 = 35.1999 gradi.
        assertEquals(35_199_900L, (scalar("SELECT latE6 FROM poi WHERE id = 2000") as Number).toLong())
    }
}
