package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

class AddressSearchTest {

    @Test
    fun `streetKey toglie maiuscole, accenti e punteggiatura`() {
        assertEquals("via dell universita 3", streetKey("Via Dell'Univérsità, 3"))
        assertEquals("rue de l eglise", streetKey("  Rue de l’Église  "))
        // Nessuna trascrizione: la ß resta com'e'.
        assertEquals("straße des 17 juni", streetKey("Straße des 17. Juni"))
    }

    @Test
    fun `streetKey riduce ogni sequenza di separatori a un solo spazio e toglie quelli ai bordi`() {
        assertEquals("avenida 9 de julio", streetKey("--Avenida 9 de   Julio--"))
        assertEquals("a b c", streetKey("a/b\tc"))
        assertEquals("", streetKey(" - . "))
    }

    @Test
    fun `streetKey tiene lettere e cifre di altri alfabeti e toglie i segni combinanti`() {
        // Cirillico e CJK restano; la i breve cirillica (й) si scompone in и + segno e perde il segno.
        assertEquals("улица ленина", streetKey("Улица Ленина"))
        assertEquals("и", streetKey("й"))
        assertEquals("中山路 2", streetKey("中山路, 2"))
        // I (con punto sopra) minuscola con Locale.ROOT diventa "i" + punto combinante: il punto sparisce.
        assertEquals("istanbul", streetKey("İstanbul"))
    }

    private fun road(name: String, vararg points: Pair<Int, Int>) = Road(name, points.toList())

    // A lat 43.94 un grado di latitudine sono 110.540 m: 1 m = ~9 microgradi.
    private val roadsIndex = RoadIndex(
        listOf(
            // Segmento est-ovest a lat 43.940000, da lon 12.440000 a 12.450000 (~800 m).
            road("Via Ovest-Est", 43_940_000 to 12_440_000, 43_940_000 to 12_450_000),
            // Segmento nord-sud a lon 12.445000, a nord della prima (lat 43.941000..43.942000).
            road("Via Nord-Sud", 43_941_000 to 12_445_000, 43_942_000 to 12_445_000),
        ),
    )

    @Test
    fun `la via della fonte vince sulla strada piu' vicina`() {
        val address = Address(43_940_050, 12_445_000, "1", street = "Via della Fonte", city = "Serravalle")

        val result = assignStreets(listOf(address), roadsIndex)

        assertEquals(listOf(address), result)
    }

    @Test
    fun `senza via della fonte si prende la strada con nome piu' vicina entro 50 m`() {
        // ~22 m a nord di Via Ovest-Est (200 microgradi di latitudine = ~22 m).
        val near = Address(43_940_200, 12_443_000, "5")
        // ~44 m: dentro i 50 m, e piu' vicina a Via Ovest-Est che a nessun'altra.
        val edge = Address(43_940_400, 12_447_000, "7")

        val result = assignStreets(listOf(near, edge), roadsIndex)

        assertEquals(listOf("Via Ovest-Est", "Via Ovest-Est"), result.map { it.street })
    }

    @Test
    fun `oltre 50 m non si assegna nessuna via`() {
        // 600 microgradi di latitudine = ~66 m dalla strada piu' vicina.
        val far = Address(43_940_600, 12_443_000, "9")

        val result = assignStreets(listOf(far), roadsIndex)

        assertNull(result.single().street)
    }

    @Test
    fun `tra due strade vince la piu' vicina, anche oltre l'estremo di un segmento`() {
        val nearSecond = Address(43_940_900, 12_445_050, "3")
        // A est della fine del primo segmento (12.450000): la distanza e' quella dall'estremo, ~30 m.
        val pastEnd = Address(43_940_000, 12_450_400, "4")
        // Oltre l'estremo piu' di 50 m: niente.
        val tooFarPastEnd = Address(43_940_000, 12_450_900, "6")

        val result = assignStreets(listOf(nearSecond, pastEnd, tooFarPastEnd), roadsIndex)

        assertEquals(listOf("Via Nord-Sud", "Via Ovest-Est", null), result.map { it.street })
    }

    @Test
    fun `senza strade nessun civico prende una via`() {
        val result = assignStreets(listOf(Address(1, 1, "1"), Address(2, 2, "2", street = "Via X")), RoadIndex(emptyList()))

        assertEquals(listOf(null, "Via X"), result.map { it.street })
    }

    @Test
    fun `le strade del fixture di San Marino hanno un nome e cadono vicino ai civici`() {
        val fixture = File("testdata/san-marino-center-z15.pmtiles")
        val roads = extractRoads(fixture)
        assertTrue("attese strade con nome, trovate ${roads.size}", roads.size > 20)
        assertTrue(roads.all { it.name.isNotBlank() && it.points.size >= 2 })

        val addresses = extractAddresses(fixture, 12.44, 43.93, 12.46, 43.94)
        val withStreets = assignStreets(addresses, RoadIndex(roads))
        // Nel centro storico quasi ogni civico ha una strada con nome a pochi metri.
        assertTrue(withStreets.count { it.street != null } > addresses.size / 2)
        assertTrue(withStreets.filter { it.street != null }.all { it.street == it.street!!.trim() && it.street!!.isNotEmpty() })
    }

    private fun withDb(block: (File) -> Unit) {
        val db = File.createTempFile("pocket-travel-test", ".addresses-search.db")
        try {
            block(db)
        } finally {
            db.delete()
        }
    }

    private fun <T> query(db: File, sql: String, read: (java.sql.ResultSet) -> T): List<T> =
        DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
            conn.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    val rows = mutableListOf<T>()
                    while (rs.next()) rows += read(rs)
                    rows
                }
            }
        }

    @Test
    fun `scrive schema, meta e righe solo per i civici con una via`() = withDb { db ->
        val addresses = listOf(
            Address(43_942_400, 12_457_800, "12", street = "Via dell'Università", city = "San Marino"),
            Address(43_942_500, 12_457_900, "3A", street = "Via Roma"),
            Address(43_942_600, 12_458_000, "5"),
            Address(43_942_700, 12_458_100, "6", street = "  "),
            Address(43_942_800, 12_458_200, "7", street = "- . -"),
        )

        val written = writeAddressSearchDb(addresses, db)

        assertEquals(2, written)
        // Vie in ordine di (key, name, city): "via dell universita" prima di "via roma", id da 1.
        val streets = query(db, "SELECT id, name, key, city FROM street ORDER BY id") {
            listOf(it.getInt(1), it.getString(2), it.getString(3), it.getString(4))
        }
        assertEquals(
            listOf(
                listOf(1, "Via dell'Università", "via dell universita", "San Marino"),
                listOf(2, "Via Roma", "via roma", null),
            ),
            streets,
        )
        // Origine = minimi di latE6/lonE6; le posizioni sono scarti non negativi da quella.
        val addressRows = query(db, "SELECT street_id, number, dlat, dlon FROM address ORDER BY street_id, number") {
            listOf(it.getInt(1), it.getString(2), it.getInt(3), it.getInt(4))
        }
        assertEquals(listOf(listOf(1, "12", 0, 0), listOf(2, "3A", 100, 100)), addressRows)
        assertEquals(
            mapOf("format" to "2", "origin_lat_e6" to "43942400", "origin_lon_e6" to "12457800"),
            query(db, "SELECT key, value FROM meta") { it.getString(1) to it.getString(2) }.toMap(),
        )
        assertEquals(
            listOf("street_key"),
            query(db, "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'street' AND name NOT LIKE 'sqlite_%'") { it.getString(1) },
        )
        // La ricerca per via usa l'indice street_key e poi la chiave primaria di address, senza scorrere le tabelle.
        val streetPlan = query(db, "EXPLAIN QUERY PLAN SELECT id FROM street WHERE key = 'via roma'") { it.getString(4) }
        assertTrue(streetPlan.toString(), streetPlan.any { it.contains("street_key") })
        val addressPlan = query(db, "EXPLAIN QUERY PLAN SELECT dlat, dlon FROM address WHERE street_id = 2 AND number = '3a'") { it.getString(4) }
        assertTrue(addressPlan.toString(), addressPlan.none { it.startsWith("SCAN") })
        // Il civico si confronta senza badare alle maiuscole (NOCASE).
        assertEquals(listOf(1), query(db, "SELECT count(*) FROM address WHERE street_id = 2 AND number = '3a'") { it.getInt(1) })
        // Giornale di rollback (non WAL) e pagine da 1 KiB: l'app apre il file in sola lettura.
        assertEquals(listOf("delete"), query(db, "PRAGMA journal_mode") { it.getString(1) })
        assertEquals(listOf(1024), query(db, "PRAGMA page_size") { it.getInt(1) })
    }

    @Test
    fun `una riga per via e citta' distinte e un solo civico per posizione`() = withDb { db ->
        val addresses = listOf(
            Address(43_942_400, 12_457_800, "7", street = "Via Roma", city = "A"),
            Address(43_942_900, 12_457_900, "7", street = "Via Roma", city = "B"),
            // Stessa via, civico (senza badare alle maiuscole) e posizione: duplicato.
            Address(43_942_400, 12_457_800, "7a", street = "Via Roma", city = "A"),
            Address(43_942_400, 12_457_800, "7A", street = "Via Roma", city = "A"),
        )

        val written = writeAddressSearchDb(addresses, db)

        assertEquals(3, written)
        assertEquals(listOf(listOf(1, "A"), listOf(2, "B")), query(db, "SELECT id, city FROM street ORDER BY id") { listOf(it.getInt(1), it.getString(2)) })
        // Dei due duplicati resta il primo in ordine di byte: "7A" < "7a".
        assertEquals(listOf("7", "7A"), query(db, "SELECT number FROM address WHERE street_id = 1 ORDER BY number") { it.getString(1) })
    }

    @Test
    fun `lo stesso contenuto da' gli stessi byte e il file e' sostituito, non accodato`() = withDb { db ->
        val addresses = listOf(
            Address(43_942_500, 12_457_900, "3A", street = "Via Roma"),
            Address(43_942_400, 12_457_800, "12", street = "Via Roma"),
        )

        writeAddressSearchDb(addresses, db)
        val first = db.readBytes()
        writeAddressSearchDb(addresses.reversed(), db)

        assertTrue("stessi byte a prescindere dall'ordine di ingresso", first.contentEquals(db.readBytes()))
        assertEquals(listOf(2), query(db, "SELECT count(*) FROM address") { it.getInt(1) })
    }

    // --- streetKey: casi limite ---

    @Test
    fun `streetKey - vuoto, solo cifre, emoji e trattini bassi come separatori`() {
        assertEquals("", streetKey(""))
        assertEquals("", streetKey("😀"))
        assertEquals("12 34", streetKey("12_34"))
        assertEquals("via roma", streetKey("Via 😀 Roma"))
        assertEquals("via roma", streetKey("Via Roma"))
        assertEquals("via roma", streetKey("via roma"))
    }

    @Test
    fun `streetKey - accenti latini scomposti, lettere senza scomposizione e cifre di altri alfabeti`() {
        assertEquals("aeiou", streetKey("ÀÉÎÕÜ"))
        assertEquals("n", streetKey("ñ"))
        // La ł polacca non si scompone: resta; la ó e la ź perdono l'accento.
        assertEquals("łodz", streetKey("Łódź"))
        // Cifre arabo-indiche: sono cifre per Character.isLetterOrDigit.
        assertEquals("شارع ١٢", streetKey("شارع ١٢"))
    }

    @Test
    fun `streetKey - e' idempotente e la stessa chiave per scritture equivalenti`() {
        listOf("Via Dell'Univérsità, 3", "  Rue de l’Église  ", "Straße des 17. Juni", "Улица Ленина", "-- . --").forEach { street ->
            assertEquals(streetKey(street), streetKey(streetKey(street)))
        }
        // La e accentata precomposta e quella scomposta (e + accento) danno la stessa chiave.
        assertEquals(streetKey("café"), streetKey("café"))
        assertEquals(streetKey("VIA ROMA"), streetKey("via roma"))
    }

    // --- RoadIndex e assignStreets: casi limite ---

    @Test
    fun `un indice senza strade o con strade senza segmenti non trova nulla`() {
        assertNull(RoadIndex(emptyList()).nearestName(43_940_000, 12_445_000))
        assertNull(RoadIndex(listOf(Road("Vuota", emptyList()))).nearestName(43_940_000, 12_445_000))
        assertNull(RoadIndex(listOf(Road("Un punto", listOf(43_940_000 to 12_445_000)))).nearestName(43_940_000, 12_445_000))
    }

    @Test
    fun `un segmento di lunghezza zero conta come un punto e maxMeters e' il limite`() {
        val index = RoadIndex(listOf(Road("Punto", listOf(43_940_000 to 12_445_000, 43_940_000 to 12_445_000))))

        assertEquals("Punto", index.nearestName(43_940_000, 12_445_000, maxMeters = 0.0))
        // 100 microgradi di latitudine = ~11 m.
        assertEquals("Punto", index.nearestName(43_940_100, 12_445_000))
        assertNull(index.nearestName(43_940_100, 12_445_000, maxMeters = 5.0))
        assertEquals("Punto", index.nearestName(43_940_100, 12_445_000, maxMeters = 12.0))
    }

    @Test
    fun `una strada lunga attraversa piu' riquadri e si trova lungo tutto il percorso`() {
        // ~4,8 km est-ovest e una diagonale di una decina di km: ben piu' dei 100 m di un riquadro.
        val index = RoadIndex(
            listOf(
                Road("Lunga", listOf(43_940_000 to 12_400_000, 43_940_000 to 12_460_000)),
                Road("Diagonale", listOf(43_900_000 to 12_400_000, 43_980_000 to 12_480_000)),
            ),
        )

        assertEquals("Lunga", index.nearestName(43_940_100, 12_430_000))
        assertEquals("Lunga", index.nearestName(43_939_900, 12_459_000))
        // Punti sulla diagonale (stessi gradi di scarto in latitudine e longitudine dall'inizio).
        assertEquals("Diagonale", index.nearestName(43_960_000, 12_460_000))
        assertEquals("Diagonale", index.nearestName(43_970_000, 12_470_000))
        assertNull(index.nearestName(43_920_000, 12_470_000))
    }

    @Test
    fun `tra due strade vince la piu' vicina anche se e' la seconda`() {
        val index = RoadIndex(
            listOf(
                Road("Lontana", listOf(43_940_400 to 12_440_000, 43_940_400 to 12_450_000)),
                Road("Vicina", listOf(43_940_100 to 12_440_000, 43_940_100 to 12_450_000)),
            ),
        )

        assertEquals("Vicina", index.nearestName(43_940_000, 12_445_000))
    }

    @Test
    fun `l'indice funziona nell'emisfero sud e a ovest del meridiano zero`() {
        // Santiago del Cile: lat e lon negative, il riquadro si calcola con floor e non con il troncamento.
        val index = RoadIndex(listOf(Road("Alameda", listOf(-33_450_000 to -70_660_000, -33_450_000 to -70_640_000))))

        // 100 microgradi = ~11 m a nord e a sud della strada.
        assertEquals("Alameda", index.nearestName(-33_449_900, -70_650_000))
        assertEquals("Alameda", index.nearestName(-33_450_100, -70_650_000))
        assertNull(index.nearestName(-33_451_000, -70_650_000))
    }

    @Test
    fun `assignStreets - via della fonte con spazi si ripulisce, via di soli spazi usa la strada, citta' e ordine si conservano`() {
        val addresses = listOf(
            Address(43_940_050, 12_445_000, "1", street = "  Via della Fonte  ", city = "Serravalle"),
            Address(43_940_050, 12_445_100, "2", street = "   ", city = "Faetano"),
            Address(43_940_050, 12_445_200, "3", street = ""),
            Address(40_000_000, 10_000_000, "4", street = "   "),
        )

        val result = assignStreets(addresses, roadsIndex)

        assertEquals(listOf("Via della Fonte", "Via Ovest-Est", "Via Ovest-Est", null), result.map { it.street })
        assertEquals(listOf("Serravalle", "Faetano", null, null), result.map { it.city })
        assertEquals(listOf("1", "2", "3", "4"), result.map { it.number })
        assertEquals(emptyList<Address>(), assignStreets(emptyList(), roadsIndex))
    }

    @Test
    fun `assignStreets restituisce lo stesso oggetto se la via non cambia`() {
        val address = Address(43_940_050, 12_445_000, "1", street = "Via X")
        val result = assignStreets(listOf(address), roadsIndex).single()

        assertTrue(result === address)
    }

    // --- writeAddressSearchDb: casi limite ---

    @Test
    fun `senza civici con una via scrive un file con le sole tabelle e restituisce zero`() = withDb { db ->
        val written = writeAddressSearchDb(listOf(Address(1, 1, "1"), Address(2, 2, "2", street = " - ")), db)

        assertEquals(0, written)
        assertEquals(listOf(0), query(db, "SELECT count(*) FROM address") { it.getInt(1) })
        assertEquals(listOf(0), query(db, "SELECT count(*) FROM street") { it.getInt(1) })
        assertEquals(
            mapOf("format" to "2", "origin_lat_e6" to "0", "origin_lon_e6" to "0"),
            query(db, "SELECT key, value FROM meta") { it.getString(1) to it.getString(2) }.toMap(),
        )
    }

    @Test
    fun `l'origine con coordinate negative e gli scarti restano non negativi`() = withDb { db ->
        val addresses = listOf(
            Address(-33_868_800, -70_000_000, "1", street = "Calle A"),
            Address(-33_868_700, -69_999_500, "2", street = "Calle A"),
            Address(-33_900_000, -70_001_000, "3", street = "Calle B"),
        )

        writeAddressSearchDb(addresses, db)

        assertEquals(
            mapOf("origin_lat_e6" to "-33900000", "origin_lon_e6" to "-70001000"),
            query(db, "SELECT key, value FROM meta WHERE key LIKE 'origin%'") { it.getString(1) to it.getString(2) }.toMap(),
        )
        val offsets = query(db, "SELECT number, dlat, dlon FROM address ORDER BY number") { listOf(it.getString(1), it.getInt(2), it.getInt(3)) }
        assertEquals(listOf(listOf("1", 31_200, 1_000), listOf("2", 31_300, 1_500), listOf("3", 0, 0)), offsets)
        assertTrue(offsets.all { (it[1] as Int) >= 0 && (it[2] as Int) >= 0 })
    }

    @Test
    fun `via e citta' si ripuliscono dagli spazi e la citta' vuota e' NULL`() = withDb { db ->
        writeAddressSearchDb(
            listOf(
                Address(43_942_400, 12_457_800, "1", street = "  Via Roma  ", city = "  Serravalle  "),
                Address(43_942_500, 12_457_900, "2", street = "Via Po", city = "   "),
            ),
            db,
        )

        assertEquals(
            listOf(listOf("Via Po", "via po", null), listOf("Via Roma", "via roma", "Serravalle")),
            query(db, "SELECT name, key, city FROM street ORDER BY id") { listOf(it.getString(1), it.getString(2), it.getString(3)) },
        )
    }

    @Test
    fun `vie con la stessa chiave ma nome diverso restano distinte, a parita' di chiave e nome conta la citta'`() = withDb { db ->
        writeAddressSearchDb(
            listOf(
                Address(43_942_400, 12_457_800, "1", street = "Via Università"),
                Address(43_942_410, 12_457_810, "2", street = "Via Universita"),
                Address(43_942_420, 12_457_820, "3", street = "Via Roma", city = "Beta"),
                Address(43_942_430, 12_457_830, "4", street = "Via Roma"),
                Address(43_942_440, 12_457_840, "5", street = "Via Roma", city = "Alfa"),
            ),
            db,
        )

        // Ordine (key, name, city), con la citta' assente prima di quelle presenti.
        assertEquals(
            listOf(
                listOf("via roma", "Via Roma", null), listOf("via roma", "Via Roma", "Alfa"), listOf("via roma", "Via Roma", "Beta"),
                listOf("via universita", "Via Universita", null), listOf("via universita", "Via Università", null),
            ),
            query(db, "SELECT key, name, city FROM street ORDER BY id") { listOf(it.getString(1), it.getString(2), it.getString(3)) },
        )
    }

    @Test
    fun `il confronto NOCASE dei civici vale solo per l'ASCII`() = withDb { db ->
        val written = writeAddressSearchDb(
            listOf(
                Address(43_942_400, 12_457_800, "3à", street = "Via Roma"),
                Address(43_942_400, 12_457_800, "3À", street = "Via Roma"),
                Address(43_942_400, 12_457_800, "3a", street = "Via Roma"),
                Address(43_942_400, 12_457_800, "3A", street = "Via Roma"),
            ),
            db,
        )

        // 3a/3A sono un civico solo; 3à e 3À restano due (SQLite ripiega solo le lettere ASCII).
        assertEquals(3, written)
        assertEquals(listOf(3), query(db, "SELECT count(*) FROM address") { it.getInt(1) })
    }

    @Test
    fun `nomi di vie unicode si conservano e la chiave e' calcolata sul nome ripulito`() = withDb { db ->
        writeAddressSearchDb(listOf(Address(43_942_400, 12_457_800, "1", street = "Улица Ленина")), db)

        assertEquals(
            listOf(listOf("Улица Ленина", "улица ленина")),
            query(db, "SELECT name, key FROM street") { listOf(it.getString(1), it.getString(2)) },
        )
    }

    @Test
    fun `un nome di via con apici o SQL si salva alla lettera`() = withDb { db ->
        val tricky = "Via d'Annunzio'); DROP TABLE address;--"
        writeAddressSearchDb(listOf(Address(43_942_400, 12_457_800, "1", street = tricky)), db)

        assertEquals(listOf(tricky), query(db, "SELECT name FROM street") { it.getString(1) })
        assertEquals(listOf(1), query(db, "SELECT count(*) FROM address") { it.getInt(1) })
    }

    // --- extractRoads su tile costruite a mano ---

    // Tile z15 (16384, 16384): la linea orizzontale (0,128)-(256,128) va da lon 0 a 0,010986 a lat -0,005493.
    private val originTile = com.onthegomap.planetiler.geo.TileCoord.ofXYZ(16384, 16384, 15)

    private fun roadFeature(name: String?, kind: String = "minor_road", detail: String? = null, geometry: org.locationtech.jts.geom.Geometry = tileLine(0.0 to 128.0, 256.0 to 128.0)): TestFeature {
        val tags = mutableMapOf<String, Any>("kind" to kind)
        if (name != null) tags["name"] = name
        if (detail != null) tags["kind_detail"] = detail
        return TestFeature(geometry, tags)
    }

    private fun withRoadsPmtiles(tiles: Map<com.onthegomap.planetiler.geo.TileCoord, com.onthegomap.planetiler.VectorTile>, block: (File) -> Unit) {
        val file = File.createTempFile("pocket-travel-test", ".pmtiles")
        try {
            writeTestPmtiles(file, tiles)
            block(file)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `extractRoads - solo le strade con nome del layer roads, senza ferrovie, traghetti e piste`() {
        val tile = testTile(
            mapOf(
                "roads" to listOf(
                    roadFeature("Via Roma"),
                    roadFeature("  Via Spazi  "),
                    roadFeature(null),
                    roadFeature("   "),
                    roadFeature("Ferrovia", kind = "rail"),
                    roadFeature("Traghetto", kind = "ferry"),
                    roadFeature("Pista", detail = "runway"),
                    roadFeature("Rullaggio", detail = "taxiway"),
                    roadFeature("Sentiero", kind = "path", detail = "footway"),
                    roadFeature("Un punto", geometry = tilePoint(128.0, 128.0)),
                ),
                "places" to listOf(roadFeature("Altro layer")),
            ),
        )

        withRoadsPmtiles(mapOf(originTile to tile)) { file ->
            val roads = extractRoads(file)

            assertEquals(listOf("Sentiero", "Via Roma", "Via Spazi"), roads.map { it.name }.sorted())
        }
    }

    @Test
    fun `extractRoads - le coordinate dei punti sono quelle geografiche della tile`() {
        val tile = testTile(mapOf("roads" to listOf(roadFeature("Via Roma"))))

        withRoadsPmtiles(mapOf(originTile to tile)) { file ->
            val road = extractRoads(file).single()

            assertEquals(2, road.points.size)
            // Da lon 0 a 0,010986 (360/32768 gradi per tile) a lat -0,005493.
            assertTrue(road.points.all { kotlin.math.abs(it.first - (-5493)) <= 2 })
            assertTrue(kotlin.math.abs(road.points[0].second - 0) <= 2)
            assertTrue(kotlin.math.abs(road.points[1].second - 10_986) <= 2)
        }
    }

    @Test
    fun `extractRoads - una multilinea da' una strada per ogni linea e le tile non z15 si ignorano`() {
        val multi = tileMultiLine(tileLine(0.0 to 10.0, 100.0 to 10.0), tileLine(0.0 to 200.0, 100.0 to 200.0))
        val z14 = com.onthegomap.planetiler.geo.TileCoord.ofXYZ(8192, 8192, 14)

        withRoadsPmtiles(
            mapOf(
                originTile to testTile(mapOf("roads" to listOf(roadFeature("Doppia", geometry = multi)))),
                z14 to testTile(mapOf("roads" to listOf(roadFeature("Altro zoom")))),
            ),
        ) { file ->
            val roads = extractRoads(file)

            assertEquals(listOf("Doppia", "Doppia"), roads.map { it.name })
            assertTrue(roads.all { it.points.size == 2 })
        }
    }

    @Test
    fun `le strade estratte permettono di assegnare la via a un civico vicino`() {
        val tile = testTile(mapOf("roads" to listOf(roadFeature("Via Roma"))))

        withRoadsPmtiles(mapOf(originTile to tile)) { file ->
            val index = RoadIndex(extractRoads(file))

            // ~11 m a nord della strada, a meta' del tratto; poi ~100 m a nord: troppo lontano.
            assertEquals("Via Roma", index.nearestName(-5_393, 5_500))
            assertNull(index.nearestName(-4_493, 5_500))
        }
    }
}
