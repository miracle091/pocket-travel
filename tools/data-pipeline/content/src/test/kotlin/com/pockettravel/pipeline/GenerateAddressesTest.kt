package com.pockettravel.pipeline

import com.onthegomap.planetiler.VectorTile
import com.onthegomap.planetiler.geo.TileCoord
import com.onthegomap.planetiler.pmtiles.ReadablePmtiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.locationtech.jts.geom.Point
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.roundToInt

class GenerateAddressesTest {

    // Estratto reale (build Protomaps 2026-09-21) del centro di San Marino, solo z15:
    // pmtiles extract ... --bbox=12.44,43.93,12.46,43.94 --minzoom=15 --maxzoom=15
    private val fixture = File("testdata/san-marino-center-z15.pmtiles")

    @Test
    fun `estrae i numeri civici dentro il bbox con coordinate plausibili`() {
        val addresses = extractAddresses(fixture, minLon = 12.44, minLat = 43.93, maxLon = 12.46, maxLat = 43.94)

        assertTrue("attesi indirizzi nel centro di San Marino, trovati ${addresses.size}", addresses.size > 100)
        assertTrue(addresses.all { it.number.isNotBlank() })
        // Se la conversione da coordinate di tile fosse sbagliata, i punti cadrebbero fuori dal
        // bbox e verrebbero scartati: qui devono stare tutti dentro.
        assertTrue(addresses.all { it.latE6 in 43_930_000..43_940_000 && it.lonE6 in 12_440_000..12_460_000 })
    }

    @Test
    fun `un bbox piu' piccolo delle tile scarta gli indirizzi fuori`() {
        val all = extractAddresses(fixture, 12.44, 43.93, 12.46, 43.94)
        val half = extractAddresses(fixture, 12.44, 43.93, 12.45, 43.94)

        assertTrue(half.isNotEmpty() && half.size < all.size)
        assertTrue(half.all { it.lonE6 <= 12_450_000 })
    }

    @Test
    fun `scrive i civici in un pmtiles di soli punti a z14`() {
        val addresses = extractAddresses(fixture, 12.44, 43.93, 12.46, 43.94)
        val output = File.createTempFile("pocket-travel-test", ".addresses.pmtiles")
        try {
            writeAddressesPmtiles(addresses, output, 12.44, 43.93, 12.46, 43.94)

            val decoded = mutableListOf<Address>()
            ReadablePmtiles.newReadFromFile(output.toPath()).use { archive ->
                archive.allTiles.use { tiles ->
                    tiles.forEachRemaining { tile ->
                        val coord = tile.coord()
                        assertEquals(14, coord.z())
                        VectorTile.decode(GZIPInputStream(ByteArrayInputStream(tile.bytes())).readBytes()).forEach { feature ->
                            assertEquals("addresses", feature.layer())
                            val point = feature.geometry().decode() as Point
                            val lon = tileXToLon(coord.x() + point.x / 256.0, 14)
                            val lat = tileYToLat(coord.y() + point.y / 256.0, 14)
                            decoded += Address((lat * 1e6).roundToInt(), (lon * 1e6).roundToInt(), feature.tags()["number"].toString())
                        }
                    }
                }
            }

            assertEquals(addresses.size, decoded.size)
            assertEquals(addresses.map { it.number }.sorted(), decoded.map { it.number }.sorted())
            // La griglia z14 (4096 unita' per tile, ~0,6 m) sposta i punti di poco: entro 20 microgradi.
            // Ogni punto letto deve avere un originale con lo stesso numero a quella distanza (lo
            // stesso numero compare piu' volte, su vie diverse).
            val remaining = addresses.toMutableList()
            decoded.forEach { b ->
                val match = remaining.firstOrNull { a -> a.number == b.number && abs(a.latE6 - b.latE6) <= 20 && abs(a.lonE6 - b.lonE6) <= 20 }
                assertTrue("nessun originale vicino a $b", match != null)
                remaining.remove(match)
            }
        } finally {
            output.delete()
        }
    }

    @Test
    fun `conversione tile-coordinate geografiche`() {
        assertEquals(-180.0, tileXToLon(0.0, 15), 1e-9)
        assertEquals(0.0, tileXToLon(16384.0, 15), 1e-9)
        assertEquals(0.0, tileYToLat(16384.0, 15), 1e-9)
    }

    @Test
    fun `legge i civici della fonte di riserva, scartando righe incomplete e punti fuori dal bbox`() {
        val points = File.createTempFile("pocket-travel-test", ".tsv")
        try {
            points.writeText("63.7467\t-68.5170\t1020\n63.75\t-68.52\t\nnon\tnumeri\t3\n10.0\t10.0\t5\n")

            val addresses = readAddressPoints(points, -70.0, 60.0, -60.0, 70.0)

            assertEquals(listOf(Address(63_746_700, -68_517_000, "1020")), addresses)
        } finally {
            points.delete()
        }
    }

    @Test
    fun `legge i punti Overture, scartando righe incomplete e punti fuori dal bbox`() {
        val points = File.createTempFile("pocket-travel-test", ".tsv")
        try {
            points.writeText("43.9424\t12.4578\t10\tit/countrywide\n43.95\t12.46\t\tit/countrywide\n10.0\t10.0\t5\tit/countrywide\n")

            val addresses = readOvertureAddressPoints(points, 12.40, 43.89, 12.52, 43.99)

            assertEquals(listOf(OvertureAddress(43_942_400, 12_457_800, "10", "it/countrywide")), addresses)
        } finally {
            points.delete()
        }
    }

    @Test
    fun `legge via e citta' dalle colonne facoltative dei punti Overture e Overpass`() {
        val overture = File.createTempFile("pocket-travel-test", ".tsv")
        val overpass = File.createTempFile("pocket-travel-test", ".tsv")
        try {
            overture.writeText(
                "43.9424\t12.4578\t10\tit/countrywide\tVia Roma\tSerravalle\n" +
                    "43.9425\t12.4579\t11\tit/countrywide\t\t\n" +
                    "43.9426\t12.4580\t12\tit/countrywide\n",
            )
            overpass.writeText("43.9424\t12.4578\t10\tVia Roma\tSerravalle\n43.9425\t12.4579\t11\t\t\n43.9426\t12.4580\t12\n")

            val fromOverture = readOvertureAddressPoints(overture, 12.40, 43.89, 12.52, 43.99)
            val fromOverpass = readAddressPoints(overpass, 12.40, 43.89, 12.52, 43.99)

            assertEquals(
                listOf("Via Roma" to "Serravalle", null to null, null to null),
                fromOverture.map { it.street to it.city },
            )
            assertEquals(listOf("Via Roma" to "Serravalle", null to null, null to null), fromOverpass.map { it.street to it.city })
        } finally {
            overture.delete()
            overpass.delete()
        }
    }

    @Test
    fun `dedupeWithOverture porta via e citta' del punto Overture tenuto`() {
        val overture = listOf(OvertureAddress(43_942_400, 12_457_800, "12", "it/countrywide", "Via Roma", "Serravalle"))

        val result = dedupeWithOverture(emptyList(), overture)

        assertEquals(listOf(Address(43_942_400, 12_457_800, "12", "Via Roma", "Serravalle")), result)
    }

    @Test
    fun `dedupeWithOverture scarta un punto Overture vicino a un OSM con lo stesso numero`() {
        val osm = listOf(Address(43_942_400, 12_457_800, "10"))
        val overture = listOf(
            // ~15 m a nord dell'OSM, stesso numero: scartato (entro il raggio di 30 m).
            OvertureAddress(43_942_535, 12_457_800, "10", "it/countrywide"),
            // stesso punto, numero diverso: tenuto (indirizzo nuovo).
            OvertureAddress(43_942_400, 12_457_800, "12", "it/countrywide"),
            // oltre 30 m: tenuto anche se il numero e' uguale.
            OvertureAddress(43_943_500, 12_457_800, "10", "it/countrywide"),
        )

        val result = dedupeWithOverture(osm, overture)

        assertEquals(
            setOf(Address(43_942_400, 12_457_800, "10"), Address(43_942_400, 12_457_800, "12"), Address(43_943_500, 12_457_800, "10")),
            result.toSet(),
        )
    }

    @Test
    fun `dedupeWithOverture considera uguali bis ter e quater scritti per esteso o con la lettera`() {
        val osm = listOf(
            Address(43_942_400, 12_457_800, "12 bis"),
            Address(43_942_400, 12_457_900, "7bis"),
            Address(43_942_400, 12_458_000, "5 Ter"),
            Address(43_942_400, 12_458_100, "3 quater"),
        )
        val overture = listOf(
            OvertureAddress(43_942_400, 12_457_800, "12B", "fr/statewide"),
            OvertureAddress(43_942_400, 12_457_900, "7B", "fr/statewide"),
            OvertureAddress(43_942_400, 12_458_000, "5T", "fr/statewide"),
            OvertureAddress(43_942_400, 12_458_100, "3Q", "fr/statewide"),
        )

        assertEquals(osm, dedupeWithOverture(osm, overture))
    }

    @Test
    fun `dedupeWithOverture tiene i civici che differiscono per la sola lettera o per bis contro ter`() {
        val osm = listOf(Address(43_942_400, 12_457_800, "12"), Address(43_942_400, 12_457_800, "12 bis"))
        val overture = listOf(
            OvertureAddress(43_942_400, 12_457_800, "12A", "nl/bag"),
            OvertureAddress(43_942_400, 12_457_800, "12T", "fr/statewide"),
        )

        val result = dedupeWithOverture(osm, overture)

        assertEquals(setOf("12", "12 bis", "12A", "12T"), result.map { it.number }.toSet())
    }

    @Test
    fun `dedupeWithOverture scarta un punto Overture contenuto in una lista o in un intervallo OSM`() {
        val osm = listOf(Address(43_942_400, 12_457_800, "12;14 bis"), Address(43_942_400, 12_458_000, "20-24"))
        val overture = listOf(
            OvertureAddress(43_942_400, 12_457_800, "12", "fr/statewide"), // voce della lista
            OvertureAddress(43_942_400, 12_457_800, "14B", "fr/statewide"), // altra voce, scritta diversamente
            OvertureAddress(43_942_400, 12_458_000, "24", "nl/bag"), // estremo dell'intervallo
            OvertureAddress(43_942_400, 12_458_000, "22", "nl/bag"), // numero in mezzo: tenuto
        )

        val result = dedupeWithOverture(osm, overture)

        assertEquals(osm + Address(43_942_400, 12_458_000, "22"), result)
    }

    @Test
    fun `dedupeWithOverture toglie i doppioni interni sullo stesso punto e numero`() {
        val overture = listOf(
            OvertureAddress(43_942_400, 12_457_800, "10", "pt/countrywide"),
            OvertureAddress(43_942_400, 12_457_800, "10", "pt/countrywide"),
            OvertureAddress(43_942_401, 12_457_801, "10", "pt/countrywide"), // ~0,1 m, stessa chiave arrotondata
        )

        val result = dedupeWithOverture(emptyList(), overture)

        assertEquals(1, result.size)
    }

    @Test
    fun `addressCellContains tiene solo gli indirizzi della cella`() {
        val sanMarino = Address(43_942_400, 12_457_800, "1") // cella 12/2189/1490 (vedi build-address-cell.sh)
        val roma = Address(41_902_800, 12_496_400, "1") // fuori da quella cella

        assertTrue(addressCellContains(sanMarino, CellId(12, 2189, 1490)))
        assertFalse(addressCellContains(roma, CellId(12, 2189, 1490)))
        // Una cella meno profonda (z inferiore) e' un antenato: la contiene comunque.
        assertTrue(addressCellContains(sanMarino, CellId(10, 547, 372)))
    }

    @Test
    fun `parseCellId legge z x y e rifiuta un formato diverso`() {
        assertEquals(CellId(12, 2189, 1490), parseCellId("12/2189/1490"))
        assertThrows(IllegalArgumentException::class.java) { parseCellId("12/2189") }
    }

    // --- extractAddresses su tile costruite a mano ---

    // Tile z15 (16384, 16384): a cavallo dell'equatore e del meridiano zero, quindi il punto (128, 128) cade a circa
    // lat -0,005493, lon 0,005493 (-5493 e 5493 microgradi).
    private val originTile = TileCoord.ofXYZ(16384, 16384, 15)

    private fun address(number: String, x: Double, y: Double, kind: String = "address", extra: Map<String, Any> = emptyMap()) =
        TestFeature(tilePoint(x, y), mapOf<String, Any>("kind" to kind, "addr_housenumber" to number) + extra)

    private fun near(a: Address, latE6: Int, lonE6: Int) = abs(a.latE6 - latE6) <= 2 && abs(a.lonE6 - lonE6) <= 2

    private fun withPmtiles(tiles: Map<TileCoord, com.onthegomap.planetiler.VectorTile>, block: (File) -> Unit) {
        val file = File.createTempFile("pocket-travel-test", ".pmtiles")
        try {
            writeTestPmtiles(file, tiles)
            block(file)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `extractAddresses tiene solo i punti del layer buildings con kind address e un numero non vuoto`() {
        val tile = testTile(
            mapOf(
                "buildings" to listOf(
                    address("12", 128.0, 128.0),
                    address(" 14 ", 64.0, 64.0),
                    address("", 10.0, 10.0),
                    address("   ", 11.0, 11.0),
                    address("99", 12.0, 12.0, kind = "building"),
                    TestFeature(tilePoint(13.0, 13.0), mapOf("kind" to "address")),
                    TestFeature(tileLine(20.0 to 20.0, 30.0 to 30.0), mapOf("kind" to "address", "addr_housenumber" to "77")),
                    address("16", 192.0, 64.0, extra = mapOf("addr_housenumber" to 16L)),
                ),
                "roads" to listOf(address("55", 100.0, 100.0)),
            ),
        )

        withPmtiles(mapOf(originTile to tile)) { file ->
            val addresses = extractAddresses(file, -1.0, -1.0, 1.0, 1.0)

            assertEquals(listOf("12", "14", "16"), addresses.map { it.number }.sorted())
            assertTrue(addresses.all { it.street == null && it.city == null })
            assertTrue(addresses.first { it.number == "12" }.let { near(it, -5493, 5493) })
            assertTrue(addresses.first { it.number == "14" }.let { near(it, -2747, 2747) })
            assertTrue(addresses.first { it.number == "16" }.let { near(it, -2747, 8240) })
        }
    }

    @Test
    fun `extractAddresses ignora le tile che non sono a z15`() {
        val z14 = TileCoord.ofXYZ(8192, 8192, 14)
        val tile = testTile(mapOf("buildings" to listOf(address("1", 128.0, 128.0))))

        withPmtiles(mapOf(z14 to tile, originTile to testTile(mapOf("buildings" to listOf(address("2", 128.0, 128.0)))))) { file ->
            assertEquals(listOf("2"), extractAddresses(file, -1.0, -1.0, 1.0, 1.0).map { it.number })
        }
    }

    @Test
    fun `extractAddresses applica il bbox ai punti e un bbox lontano o invertito non da' nulla`() {
        val tile = testTile(mapOf("buildings" to listOf(address("12", 128.0, 128.0), address("14", 64.0, 64.0), address("16", 192.0, 64.0))))

        withPmtiles(mapOf(originTile to tile)) { file ->
            // Solo il (128, 128): lon 0,005..0,006 e lat -0,006..-0,005.
            assertEquals(listOf("12"), extractAddresses(file, 0.005, -0.006, 0.006, -0.005).map { it.number })
            assertEquals(3, extractAddresses(file, -180.0, -90.0, 180.0, 90.0).size)
            assertEquals(emptyList<Address>(), extractAddresses(file, 10.0, 10.0, 11.0, 11.0))
            assertEquals(emptyList<Address>(), extractAddresses(file, 1.0, 1.0, -1.0, -1.0))
        }
    }

    @Test
    fun `extractAddresses legge piu' tile e conserva i numeri unicode`() {
        val other = TileCoord.ofXYZ(16385, 16384, 15)
        val first = testTile(mapOf("buildings" to listOf(address("12/Ä", 128.0, 128.0))))
        val second = testTile(mapOf("buildings" to listOf(address("東京 3", 128.0, 128.0))))

        withPmtiles(mapOf(originTile to first, other to second)) { file ->
            val numbers = extractAddresses(file, -1.0, -1.0, 1.0, 1.0).map { it.number }.toSet()

            assertEquals(setOf("12/Ä", "東京 3"), numbers)
        }
    }

    // --- readAddressPoints / readOvertureAddressPoints: casi limite ---

    private fun withTsv(text: String, block: (File) -> Unit) {
        val file = File.createTempFile("pocket-travel-test", ".tsv")
        try {
            file.writeText(text)
            block(file)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `readAddressPoints - bbox con bordi inclusi, numero ripulito dagli spazi, righe vuote e valori non numerici`() {
        val text = "\n43.9\t12.4\t  12  \n43.99\t12.52\t13\n43.9000001\t12.4\t14\n43,95\t12.45\t15\nNaN\t12.45\t16\n43.95\tInfinity\t17\n43.95\t12.45\t\t\t\n   \n"
        withTsv(text) { file ->
            val result = readAddressPoints(file, 12.4, 43.9, 12.52, 43.99)

            assertEquals(listOf("12", "13", "14"), result.map { it.number })
            assertEquals(Address(43_900_000, 12_400_000, "12"), result[0])
        }
    }

    @Test
    fun `readAddressPoints - via e citta' vuote o di soli spazi sono assenti, quelle piene si ripuliscono e conservano unicode`() {
        withTsv("43.95\t12.45\t1\t  Via Ångström  \t  Città 東京 \t\n43.95\t12.45\t2\t   \t   \n") { file ->
            val result = readAddressPoints(file, 12.4, 43.9, 12.52, 43.99)

            assertEquals(listOf("Via Ångström" to "Città 東京", null to null), result.map { it.street to it.city })
        }
    }

    @Test
    fun `readAddressPoints e readOvertureAddressPoints su file vuoto non danno punti`() {
        withTsv("") { file ->
            assertEquals(emptyList<Address>(), readAddressPoints(file, -180.0, -90.0, 180.0, 90.0))
            assertEquals(emptyList<OvertureAddress>(), readOvertureAddressPoints(file, -180.0, -90.0, 180.0, 90.0))
        }
    }

    @Test
    fun `readOvertureAddressPoints - il dataset e' ripulito e puo' mancare, via e citta' sono nelle colonne 5 e 6`() {
        withTsv("43.95\t12.45\t1\t  it/countrywide  \tVia A\tCitta A\n43.95\t12.45\t2\n43.95\t12.45\t3\t\t \t\n") { file ->
            val result = readOvertureAddressPoints(file, 12.4, 43.9, 12.52, 43.99)

            assertEquals(listOf("it/countrywide", "", ""), result.map { it.dataset })
            assertEquals(listOf("Via A" to "Citta A", null to null, null to null), result.map { it.street to it.city })
        }
    }

    // --- dedupeWithOverture: casi limite ---

    @Test
    fun `dedupeWithOverture con elenchi vuoti, solo OSM o solo Overture`() {
        assertEquals(emptyList<Address>(), dedupeWithOverture(emptyList(), emptyList()))
        val osm = listOf(Address(43_942_400, 12_457_800, "1"), Address(43_942_500, 12_457_900, "2"))
        assertEquals(osm, dedupeWithOverture(osm, emptyList()))
        val overture = listOf(OvertureAddress(43_942_400, 12_457_800, "1", "x"), OvertureAddress(43_942_500, 12_457_900, "2", "x"))
        assertEquals(overture.map { Address(it.latE6, it.lonE6, it.number) }, dedupeWithOverture(emptyList(), overture))
    }

    @Test
    fun `dedupeWithOverture mette prima i civici OSM nell'ordine di ingresso, poi quelli Overture nuovi`() {
        val osm = listOf(Address(43_942_900, 12_457_900, "9"), Address(43_942_400, 12_457_800, "1"))
        val overture = listOf(OvertureAddress(43_000_000, 12_000_000, "5", "x"), OvertureAddress(43_100_000, 12_100_000, "4", "x"))

        val result = dedupeWithOverture(osm, overture)

        assertEquals(listOf("9", "1", "5", "4"), result.map { it.number })
    }

    @Test
    fun `dedupeWithOverture - due OSM identici per punto e numero ne tengono uno, il primo`() {
        val osm = listOf(
            Address(43_942_400, 12_457_800, "10", street = "Via Prima"),
            Address(43_942_401, 12_457_801, "10", street = "Via Seconda"),
        )

        assertEquals(listOf(osm[0]), dedupeWithOverture(osm, emptyList()))
    }

    @Test
    fun `dedupeWithOverture - il raggio e' di 30 m in ogni direzione e anche a cavallo delle celle della griglia`() {
        // 22 m (200 microgradi di latitudine) dal civico OSM, con lo stesso numero: scartato per ogni posizione
        // della griglia di confronto, anche quando il punto cade nella cella accanto.
        val baseLats = (0 until 60).map { 43_942_400 + it * 37 }
        val osm = baseLats.mapIndexed { index, lat -> Address(lat, 12_457_800 + index * 3_000, "10") }
        val northOvertures = osm.map { OvertureAddress(it.latE6 + 200, it.lonE6, "10", "x") }
        val eastOvertures = osm.map { OvertureAddress(it.latE6, it.lonE6 + 250, "10", "x") }

        assertEquals(osm, dedupeWithOverture(osm, northOvertures))
        assertEquals(osm, dedupeWithOverture(osm, eastOvertures))
        // 31 m a nord (280 microgradi): tenuto.
        val tooFar = osm.map { OvertureAddress(it.latE6 + 280, it.lonE6, "10", "x") }
        assertEquals(osm.size * 2, dedupeWithOverture(osm, tooFar).size)
    }

    @Test
    fun `dedupeWithOverture - la distanza in longitudine tiene conto della latitudine`() {
        // A 70 gradi un grado di longitudine vale circa 38 km: 30 m sono ~790 microgradi.
        val osm = listOf(Address(70_000_000, 20_000_000, "3"))

        val inside = dedupeWithOverture(osm, listOf(OvertureAddress(70_000_000, 20_000_700, "3", "x")))
        val outside = dedupeWithOverture(osm, listOf(OvertureAddress(70_000_000, 20_000_900, "3", "x")))

        assertEquals(1, inside.size)
        assertEquals(2, outside.size)
    }

    @Test
    fun `dedupeWithOverture - maiuscole e spazi non contano e gli intervalli con lettere si confrontano agli estremi`() {
        val osm = listOf(Address(43_942_400, 12_457_800, "10a"), Address(43_942_400, 12_458_000, "20a-24b"))
        val overture = listOf(
            OvertureAddress(43_942_400, 12_457_800, " 10 A ", "x"),
            OvertureAddress(43_942_400, 12_458_000, "24B", "x"),
            OvertureAddress(43_942_400, 12_458_000, "20A", "x"),
            OvertureAddress(43_942_400, 12_458_000, "22", "x"),
        )

        val result = dedupeWithOverture(osm, overture)

        assertEquals(listOf("10a", "20a-24b", "22"), result.map { it.number })
    }

    @Test
    fun `dedupeWithOverture - lo stesso numero lontano da ogni OSM, o vicino a un OSM con numero diverso, e' un civico nuovo`() {
        val osm = listOf(Address(43_942_400, 12_457_800, "10"))
        val overture = listOf(
            OvertureAddress(44_942_400, 12_457_800, "10", "x"),
            OvertureAddress(43_942_400, 12_457_800, "11", "x"),
        )

        assertEquals(listOf("10", "10", "11"), dedupeWithOverture(osm, overture).map { it.number })
    }

    // --- CellId, addressCellContains, conversioni ---

    @Test
    fun `parseCellId - formati non validi e round trip`() {
        listOf("", "12", "12/2189", "1/2/3/4", "/1/2").forEach { spec ->
            assertThrows(Exception::class.java) { parseCellId(spec) }
        }
        assertThrows(NumberFormatException::class.java) { parseCellId("a/b/c") }
        assertThrows(NumberFormatException::class.java) { parseCellId("12/2189/ ") }
        assertEquals("12/2189/1490", parseCellId("12/2189/1490").toString())
        assertEquals(CellId(0, 0, 0), parseCellId("0/0/0"))
    }

    @Test
    fun `addressCellContains - a ogni livello una e una sola cella contiene l'indirizzo`() {
        val addresses = listOf(Address(43_942_400, 12_457_800, "1"), Address(41_902_800, 12_496_400, "1"), Address(-33_868_800, 151_209_300, "1"))

        addresses.forEach { address ->
            for (z in 0..4) {
                val n = 1 shl z
                val containing = (0 until n).flatMap { x -> (0 until n).map { y -> CellId(z, x, y) } }.filter { addressCellContains(address, it) }
                assertEquals("z$z per $address", 1, containing.size)
            }
        }
        assertTrue(addressCellContains(addresses[0], CellId(0, 0, 0)))
        assertTrue(addressCellContains(addresses[0], CellId(13, 4379, 2980)))
        assertFalse(addressCellContains(addresses[0], CellId(13, 4379, 2981)))
        assertFalse(addressCellContains(addresses[0], CellId(13, 4378, 2980)))
    }

    @Test
    fun `addressCellContains - le celle che contengono un indirizzo formano una catena di antenati`() {
        val address = Address(43_942_400, 12_457_800, "1")
        val containing = (0..4).map { z ->
            val n = 1 shl z
            (0 until n).flatMap { x -> (0 until n).map { y -> CellId(z, x, y) } }.single { addressCellContains(address, it) }
        }

        containing.zipWithNext().forEach { (parent, child) ->
            assertEquals(parent.x, child.x shr 1)
            assertEquals(parent.y, child.y shr 1)
        }
    }

    @Test
    fun `conversione tile-coordinate - estremi della proiezione e simmetria`() {
        assertEquals(180.0, tileXToLon(32768.0, 15), 1e-9)
        assertEquals(-90.0, tileXToLon(1.0, 2), 1e-9)
        assertEquals(85.0511287798, tileYToLat(0.0, 0), 1e-6)
        assertEquals(-85.0511287798, tileYToLat(1.0, 0), 1e-6)
        assertEquals(-tileYToLat(100.0, 10), tileYToLat(1024.0 - 100.0, 10), 1e-9)
    }

    // --- writeAddressesPmtiles ---

    @Test
    fun `writeAddressesPmtiles raggruppa i civici per tile z14 e conserva i numeri unicode`() {
        val sanMarino = listOf(Address(43_942_400, 12_457_800, "1"), Address(43_942_410, 12_457_810, "2/Ä"))
        val roma = Address(41_902_800, 12_496_400, "東京 3")
        val output = File.createTempFile("pocket-travel-test", ".addresses.pmtiles")
        try {
            writeAddressesPmtiles(sanMarino + roma, output, 12.0, 41.0, 13.0, 44.0)

            val byTile = mutableMapOf<String, List<String>>()
            ReadablePmtiles.newReadFromFile(output.toPath()).use { archive ->
                archive.allTiles.use { tiles ->
                    tiles.forEachRemaining { tile ->
                        val coord = tile.coord()
                        assertEquals(14, coord.z())
                        byTile["${coord.x()}/${coord.y()}"] = VectorTile.decode(GZIPInputStream(ByteArrayInputStream(tile.bytes())).readBytes())
                            .map { it.tags()["number"].toString() }.sorted()
                    }
                }
            }

            assertEquals(2, byTile.size)
            assertEquals(setOf(listOf("1", "2/Ä"), listOf("東京 3")), byTile.values.toSet())
        } finally {
            output.delete()
        }
    }

    @Test
    fun `writeAddressesPmtiles sostituisce un file preesistente`() {
        val output = File.createTempFile("pocket-travel-test", ".addresses.pmtiles").apply { writeText("vecchio contenuto") }
        try {
            writeAddressesPmtiles(listOf(Address(43_942_400, 12_457_800, "1")), output, 12.0, 43.0, 13.0, 44.0)

            var count = 0
            ReadablePmtiles.newReadFromFile(output.toPath()).use { archive -> archive.allTiles.use { it.forEachRemaining { count++ } } }
            assertEquals(1, count)
        } finally {
            output.delete()
        }
    }
}
