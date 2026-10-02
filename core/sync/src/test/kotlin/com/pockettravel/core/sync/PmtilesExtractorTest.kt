package com.pockettravel.core.sync

import ch.poole.geo.pmtiles.Constants
import ch.poole.geo.pmtiles.Hilbert
import ch.poole.geo.pmtiles.Reader
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.io.path.createTempDirectory

/**
 * Serve un file locale via HTTP range request, come farebbe build.protomaps.com — porta in
 * Kotlin lo stesso approccio del dispatcher di test del progetto pmtiles-reader stesso
 * (PMTilesDispatcher.java): risponde 200 con solo i byte richiesti, non 206 (HttpUrlConnectionChannel
 * non controlla il codice di stato, legge solo il corpo).
 */
private class RangeFileDispatcher(private val file: File) : Dispatcher() {
    /** Inizio di ogni range richiesto, per verificare quali tile sono state scaricate. */
    val requestedStarts = java.util.concurrent.CopyOnWriteArrayList<Long>()
    val requestedRanges = java.util.concurrent.CopyOnWriteArrayList<LongRange>()
    private val rangePattern = Regex("^bytes=([0-9]+)-([0-9]+)")

    override fun dispatch(request: RecordedRequest): MockResponse {
        val rangeHeader = request.getHeader("Range") ?: return MockResponse().setResponseCode(416)
        val match = rangePattern.find(rangeHeader) ?: return MockResponse().setResponseCode(416)
        val start = match.groupValues[1].toLong()
        val end = match.groupValues[2].toLong()
        requestedStarts += start
        requestedRanges += start..end
        val length = (end - start + 1).toInt()
        val buffer = ByteArray(length)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            raf.readFully(buffer)
        }
        return MockResponse().setResponseCode(200).setBody(okio.Buffer().write(buffer))
    }
}

class PmtilesExtractorTest {

    private val server = MockWebServer()
    private lateinit var sourceFile: File
    private lateinit var outputFile: File

    @Before
    fun setUp() {
        val dir = createTempDirectory("pocket-travel-pmtiles-extractor-test").toFile()
        sourceFile = File(dir, "source.pmtiles")
        outputFile = File(dir, "map.pmtiles")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fakeTile(z: Int, x: Int, y: Int): ByteArray = byteArrayOf(z.toByte(), x.toByte(), y.toByte())

    private fun tileId(z: Int, x: Int, y: Int): Long =
        ((1L shl (2 * z)) - 1L) / 3L + Hilbert.zxyToIndex(z, x.toLong(), y.toLong())

    @Test
    fun `estrae solo le tile dentro il bounding box dalla sorgente remota`() {
        // Sorgente "remota" whole-planet finta: tile su tutto il pianeta a zoom 0-1, di cui
        // solo una parte cade nel bounding box richiesto (l'emisfero ovest, x=0 a zoom 1).
        val remoteEntries = listOf(
            PmtilesEntry(tileId(0, 0, 0), fakeTile(0, 0, 0)),
            PmtilesEntry(tileId(1, 0, 0), fakeTile(1, 0, 0)), // dentro il bbox richiesto
            PmtilesEntry(tileId(1, 0, 1), fakeTile(1, 0, 1)), // dentro il bbox richiesto
            PmtilesEntry(tileId(1, 1, 0), fakeTile(1, 1, 0)), // fuori dal bbox richiesto
            PmtilesEntry(tileId(1, 1, 1), fakeTile(1, 1, 1)), // fuori dal bbox richiesto
        )
        PmtilesWriter.write(
            outputFile = sourceFile,
            entries = remoteEntries,
            metadataJson = """{"name":"whole-planet-fake"}""",
            tileCompression = Constants.COMPRESSION_GZIP,
            tileType = Constants.TYPE_MVT,
            minZoom = 0,
            maxZoom = 1,
            minLon = -180.0,
            minLat = -85.0,
            maxLon = 180.0,
            maxLat = 85.0,
        )
        server.dispatcher = RangeFileDispatcher(sourceFile)
        server.start()

        val mapSource = MapExtractionSource(
            sourceUrl = server.url("/whole-planet.pmtiles").toString(),
            minLon = -170.0, minLat = -80.0, maxLon = -10.0, maxLat = 80.0, // emisfero ovest: x=0 a zoom 1
            minZoom = 0, maxZoom = 1,
        )

        PmtilesExtractor().extract(mapSource, outputFile)

        Reader(outputFile).use { reader ->
            assertArrayEquals(fakeTile(0, 0, 0), reader.getTile(0, 0, 0))
            assertArrayEquals(fakeTile(1, 0, 0), reader.getTile(1, 0, 0))
            assertArrayEquals(fakeTile(1, 0, 1), reader.getTile(1, 0, 1))
            assertNull("una tile fuori dal bbox non deve essere stata estratta", reader.getTile(1, 1, 0))
            assertNull("una tile fuori dal bbox non deve essere stata estratta", reader.getTile(1, 1, 1))
        }
    }

    @Test
    fun `l'annullamento interrompe l'estrazione senza lasciare file temporanei`() {
        val remoteEntries = (0 until 4).flatMap { x -> (0 until 4).map { y -> PmtilesEntry(tileId(2, x, y), fakeTile(2, x, y)) } }
        PmtilesWriter.write(
            outputFile = sourceFile,
            entries = remoteEntries,
            metadataJson = """{"name":"fake"}""",
            tileCompression = Constants.COMPRESSION_GZIP,
            tileType = Constants.TYPE_MVT,
            minZoom = 2,
            maxZoom = 2,
            minLon = -180.0,
            minLat = -85.0,
            maxLon = 180.0,
            maxLat = 85.0,
        )
        server.dispatcher = RangeFileDispatcher(sourceFile)
        server.start()
        val mapSource = MapExtractionSource(
            sourceUrl = server.url("/whole-planet.pmtiles").toString(),
            minLon = -170.0, minLat = -80.0, maxLon = 170.0, maxLat = 80.0,
            minZoom = 2, maxZoom = 2,
        )

        var checks = 0
        try {
            // annullato al terzo controllo, durante la lettura dell'indice o delle tile
            PmtilesExtractor().extract(mapSource, outputFile) {
                if (++checks == 3) throw IllegalStateException("annullato")
            }
            fail("l'estrazione doveva essere interrotta")
        } catch (expected: IllegalStateException) {
            assertEquals("annullato", expected.message)
        }

        val dir = outputFile.parentFile!!
        assertTrue("nessun file temporaneo residuo", dir.listFiles()!!.none { it.name.endsWith(".tmp") })
        assertFalse("nessun archivio parziale", outputFile.exists())
    }

    @Test
    fun `l'estrazione riuscita non lascia file temporanei`() {
        val remoteEntries = listOf(PmtilesEntry(tileId(0, 0, 0), fakeTile(0, 0, 0)))
        PmtilesWriter.write(
            outputFile = sourceFile,
            entries = remoteEntries,
            metadataJson = """{"name":"fake"}""",
            tileCompression = Constants.COMPRESSION_GZIP,
            tileType = Constants.TYPE_MVT,
            minZoom = 0,
            maxZoom = 0,
            minLon = -180.0,
            minLat = -85.0,
            maxLon = 180.0,
            maxLat = 85.0,
        )
        server.dispatcher = RangeFileDispatcher(sourceFile)
        server.start()
        val mapSource = MapExtractionSource(
            sourceUrl = server.url("/whole-planet.pmtiles").toString(),
            minLon = -170.0, minLat = -80.0, maxLon = 170.0, maxLat = 80.0,
            minZoom = 0, maxZoom = 0,
        )

        PmtilesExtractor().extract(mapSource, outputFile)

        Reader(outputFile).use { reader -> assertArrayEquals(fakeTile(0, 0, 0), reader.getTile(0, 0, 0)) }
        assertEquals(
            setOf("source.pmtiles", "map.pmtiles"),
            outputFile.parentFile!!.listFiles()!!.map { it.name }.toSet(),
        )
    }

    @Test(expected = PmtilesExtractionException::class)
    fun `nessuna tile nel bbox lancia un errore esplicito`() {
        val remoteEntries = listOf(PmtilesEntry(tileId(0, 0, 0), fakeTile(0, 0, 0)))
        PmtilesWriter.write(
            outputFile = sourceFile,
            entries = remoteEntries,
            metadataJson = """{"name":"fake"}""",
            tileCompression = Constants.COMPRESSION_GZIP,
            tileType = Constants.TYPE_MVT,
            minZoom = 5,
            maxZoom = 5,
            minLon = -180.0,
            minLat = -85.0,
            maxLon = 180.0,
            maxLat = 85.0,
        )
        server.dispatcher = RangeFileDispatcher(sourceFile)
        server.start()

        // richiede solo zoom 5, dove la sorgente finta non ha nessuna tile (solo z0 esiste)
        val mapSource = MapExtractionSource(
            sourceUrl = server.url("/whole-planet.pmtiles").toString(),
            minLon = -170.0, minLat = -80.0, maxLon = -10.0, maxLat = 80.0,
            minZoom = 5, maxZoom = 5,
        )

        PmtilesExtractor().extract(mapSource, outputFile)
    }

    // --- aggiornamento incrementale: mappa installata (old) e build nuova (new), z0-2 su tutto il mondo ---

    private val worldZ0to2 = { url: String ->
        MapExtractionSource(url, minLon = -180.0, minLat = -85.0, maxLon = 180.0, maxLat = 85.0, minZoom = 0, maxZoom = 2)
    }

    /** Contenuto unico per tile (marker da z/x/y): tile diverse non condividono mai l'offset nel file. */
    private fun sizedTile(z: Int, x: Int, y: Int, size: Int): ByteArray = ByteArray(size) { (z * 16 + x * 4 + y + 1).toByte() }

    private fun writePmtiles(file: File, tiles: Map<Triple<Int, Int, Int>, ByteArray>) {
        PmtilesWriter.write(
            outputFile = file,
            entries = tiles.map { (key, data) -> PmtilesEntry(tileId(key.first, key.second, key.third), data) },
            metadataJson = """{"name":"fake"}""",
            tileCompression = Constants.COMPRESSION_GZIP,
            tileType = Constants.TYPE_MVT,
            minZoom = 0,
            maxZoom = 2,
            minLon = -180.0,
            minLat = -85.0,
            maxLon = 180.0,
            maxLat = 85.0,
        )
    }

    /** Tutte le tile z0-2 del mondo, tutte da 3 byte: la mappa "installata". x=3 a z2 manca. */
    private fun oldTiles(): Map<Triple<Int, Int, Int>, ByteArray> {
        val tiles = LinkedHashMap<Triple<Int, Int, Int>, ByteArray>()
        for (z in 0..2) for (x in 0 until (1 shl z)) for (y in 0 until (1 shl z)) {
            if (z == 2 && x == 3) continue
            tiles[Triple(z, x, y)] = sizedTile(z, x, y, 3)
        }
        return tiles
    }

    /** La build nuova: (1,0,0) e (2,0,0) cambiano lunghezza, (1,1,0) e (2,1,1) spariscono, x=3 a z2 e' nuovo. */
    private fun newTiles(): Map<Triple<Int, Int, Int>, ByteArray> {
        val tiles = LinkedHashMap(oldTiles())
        tiles[Triple(1, 0, 0)] = sizedTile(1, 0, 0, 5)
        tiles[Triple(2, 0, 0)] = sizedTile(2, 0, 0, 6)
        tiles.remove(Triple(1, 1, 0))
        tiles.remove(Triple(2, 1, 1))
        for (y in 0..3) tiles[Triple(2, 3, y)] = sizedTile(2, 3, y, 4)
        return tiles
    }

    private fun assertSameTiles(expectedFile: File, actualFile: File) {
        Reader(expectedFile).use { expected ->
            Reader(actualFile).use { actual ->
                assertEquals(expected.metadata, actual.metadata)
                for (z in 0..2) for (x in 0 until (1 shl z)) for (y in 0 until (1 shl z)) {
                    val e = expected.getTile(z, x, y)
                    val a = actual.getTile(z, x, y)
                    assertTrue("tile $z/$x/$y diversa", (e == null && a == null) || (e != null && a != null && e.contentEquals(a)))
                }
            }
        }
    }

    /** Avvia il server sulla build nuova; ritorna il dispatcher che registra i range richiesti. */
    private fun serveNew(newTiles: Map<Triple<Int, Int, Int>, ByteArray>): RangeFileDispatcher {
        writePmtiles(sourceFile, newTiles)
        return RangeFileDispatcher(sourceFile).also {
            server.dispatcher = it
            server.start()
        }
    }

    @Test
    fun `l'aggiornamento incrementale scarica solo le tile nuove o cambiate e riusa le altre`() {
        val installed = File(sourceFile.parentFile, "installed.pmtiles").also { writePmtiles(it, oldTiles()) }
        val newTiles = newTiles()
        val dispatcher = serveNew(newTiles)
        val mapSource = worldZ0to2(server.url("/planet.pmtiles").toString())

        val stats = PmtilesExtractor(alwaysDownloadMaxZoom = -1).extract(mapSource, outputFile, installed)

        // 2 tile cambiate (5 + 6 byte) e 4 nuove (4 byte l'una)
        assertTrue(stats.incremental)
        assertEquals(6, stats.tilesDownloaded)
        assertEquals(5L + 6 + 4 * 4, stats.bytesDownloaded)
        assertEquals(newTiles.size - 6, stats.tilesReused)
        assertEquals((newTiles.size - 6) * 3L, stats.bytesReused)

        // Ogni tile cambiata o nuova sta dentro un range richiesto (le tile vicine si leggono a blocchi,
        // quindi un blocco puo' comprendere anche tile invariate: il riuso lo dicono le statistiche sopra).
        val locations = RandomAccessFile(sourceFile, "r").use { raf ->
            val ids = newTiles.keys.map { tileId(it.first, it.second, it.third) }.sorted().toLongArray()
            ids.zip(PmtilesTileIndex { position, length -> ByteArray(length).also { raf.seek(position); raf.readFully(it) } }.locate(ids).offsets.toList()).toMap()
        }
        val changed = setOf(Triple(1, 0, 0), Triple(2, 0, 0)) + (0..3).map { Triple(2, 3, it) }
        changed.forEach { key ->
            val offset = locations.getValue(tileId(key.first, key.second, key.third))
            assertTrue("tile $key", dispatcher.requestedRanges.any { offset in it })
        }

        // Il risultato e' identico a un'estrazione completa dalla build nuova (tile sparite comprese).
        val full = File(sourceFile.parentFile, "full.pmtiles")
        PmtilesExtractor().extract(mapSource, full)
        assertSameTiles(full, outputFile)
        Reader(outputFile).use {
            assertNull(it.getTile(1, 1, 0))
            assertNull(it.getTile(2, 1, 1))
        }
        assertFalse("nessun file temporaneo residuo", outputFile.parentFile!!.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `l'aggiornamento incrementale scarica meno byte dell'estrazione completa`() {
        val installed = File(sourceFile.parentFile, "installed.pmtiles").also { writePmtiles(it, oldTiles()) }
        val dispatcher = serveNew(newTiles())
        val mapSource = worldZ0to2(server.url("/planet.pmtiles").toString())

        PmtilesExtractor(alwaysDownloadMaxZoom = -1).extract(mapSource, outputFile, installed)
        val incrementalBytes = dispatcher.requestedRanges.sumOf { it.last - it.first + 1 }
        dispatcher.requestedRanges.clear()
        PmtilesExtractor().extract(mapSource, File(sourceFile.parentFile, "full.pmtiles"))
        val fullBytes = dispatcher.requestedRanges.sumOf { it.last - it.first + 1 }

        assertTrue("incrementale $incrementalBytes, completa $fullBytes", incrementalBytes < fullBytes)
    }

    @Test
    fun `senza modifiche nella build non scarica nessuna tile`() {
        val installed = File(sourceFile.parentFile, "installed.pmtiles").also { writePmtiles(it, oldTiles()) }
        serveNew(oldTiles())
        val mapSource = worldZ0to2(server.url("/planet.pmtiles").toString())

        val stats = PmtilesExtractor(alwaysDownloadMaxZoom = -1).extract(mapSource, outputFile, installed)

        assertEquals(0, stats.tilesDownloaded)
        assertEquals(oldTiles().size, stats.tilesReused)
        assertSameTiles(sourceFile, outputFile)
    }

    @Test
    fun `una tile con la stessa lunghezza ma contenuto diverso resta quella installata`() {
        // Limite accettato dell'aggiornamento incrementale: il confronto e' sulla lunghezza.
        val installed = File(sourceFile.parentFile, "installed.pmtiles").also { writePmtiles(it, oldTiles()) }
        val newTiles = LinkedHashMap(oldTiles()).also { it[Triple(1, 0, 1)] = ByteArray(3) { 99 } }
        serveNew(newTiles)
        val mapSource = worldZ0to2(server.url("/planet.pmtiles").toString())

        val stats = PmtilesExtractor(alwaysDownloadMaxZoom = -1).extract(mapSource, outputFile, installed)

        assertEquals(0, stats.tilesDownloaded)
        Reader(outputFile).use { assertArrayEquals(sizedTile(1, 0, 1, 3), it.getTile(1, 0, 1)) }
    }

    @Test
    fun `fino alla zoom di soglia le tile si riscaricano anche con la stessa lunghezza`() {
        val installed = File(sourceFile.parentFile, "installed.pmtiles").also { writePmtiles(it, oldTiles()) }
        val newTiles = LinkedHashMap(oldTiles()).also {
            it[Triple(1, 0, 1)] = ByteArray(3) { 99 }
            it[Triple(2, 0, 1)] = ByteArray(3) { 98 }
        }
        serveNew(newTiles)
        val mapSource = worldZ0to2(server.url("/planet.pmtiles").toString())

        val stats = PmtilesExtractor(alwaysDownloadMaxZoom = 1).extract(mapSource, outputFile, installed)

        // z0 e z1 (1 + 4 tile) scaricate, z2 riusata: la (2,0,1) cambiata resta quella installata.
        assertEquals(5, stats.tilesDownloaded)
        assertEquals(oldTiles().size - 5, stats.tilesReused)
        Reader(outputFile).use {
            assertArrayEquals(ByteArray(3) { 99 }, it.getTile(1, 0, 1))
            assertArrayEquals(sizedTile(2, 0, 1, 3), it.getTile(2, 0, 1))
        }
    }

    @Test
    fun `l'avanzamento arriva al totale delle tile del riquadro`() {
        val installed = File(sourceFile.parentFile, "installed.pmtiles").also { writePmtiles(it, oldTiles()) }
        serveNew(newTiles())
        val mapSource = worldZ0to2(server.url("/planet.pmtiles").toString())
        val incremental = mutableListOf<Pair<Long, Long>>()
        val full = mutableListOf<Pair<Long, Long>>()

        PmtilesExtractor(alwaysDownloadMaxZoom = -1).extract(mapSource, outputFile, installed, onProgress = { done, total -> incremental += done to total })
        PmtilesExtractor().extract(mapSource, File(sourceFile.parentFile, "full.pmtiles"), onProgress = { done, total -> full += done to total })

        // In byte di tile da scaricare: le 2 cambiate (5 + 6) e le 4 nuove (4 l'una), tutte con l'estrazione completa.
        assertEquals(27L to 27L, incremental.last())
        val fullBytes = newTiles().values.sumOf { it.size.toLong() }
        assertEquals(fullBytes to fullBytes, full.last())
        assertTrue(full.zipWithNext().all { (a, b) -> a.first <= b.first })
    }

    @Test
    fun `lo spool rimasto da un processo ucciso viene cancellato`() {
        serveNew(newTiles())
        val orphan = File(outputFile.parentFile, "pmtiles-123.tiles.tmp").also { it.writeBytes(ByteArray(10)) }
        val other = File(outputFile.parentFile, "poi.db").also { it.writeBytes(ByteArray(10)) }

        PmtilesExtractor().extract(worldZ0to2(server.url("/planet.pmtiles").toString()), outputFile)

        assertFalse(orphan.exists())
        assertTrue("gli altri file dello staging restano", other.exists())
    }

    @Test
    fun `le tile vicine si leggono con una sola richiesta`() {
        serveNew(newTiles()).also { dispatcher ->
            val mapSource = worldZ0to2(server.url("/planet.pmtiles").toString())
            dispatcher.requestedStarts.clear()

            val stats = PmtilesExtractor().extract(mapSource, outputFile)

            // Tile contigue nella build: meno richieste che tile (header, directory e un solo blocco).
            assertTrue("richieste ${dispatcher.requestedStarts.size}, tile ${stats.tilesDownloaded}", dispatcher.requestedStarts.size < stats.tilesDownloaded)
            assertSameTiles(sourceFile, outputFile)
        }
    }

    @Test
    fun `con un file installato illeggibile ripiega sull'estrazione completa`() {
        val corrupt = File(sourceFile.parentFile, "installed.pmtiles").also { it.writeBytes(ByteArray(500) { i -> i.toByte() }) }
        val newTiles = newTiles()
        serveNew(newTiles)
        val mapSource = worldZ0to2(server.url("/planet.pmtiles").toString())

        val stats = PmtilesExtractor().extract(mapSource, outputFile, corrupt)

        assertFalse(stats.incremental)
        assertEquals(newTiles.size, stats.tilesDownloaded)
        assertEquals(0, stats.tilesReused)
        assertSameTiles(sourceFile, outputFile)
        assertFalse("nessun file temporaneo residuo", outputFile.parentFile!!.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `con un file installato troncato a meta ripiega sull'estrazione completa`() {
        val installed = File(sourceFile.parentFile, "installed.pmtiles").also { writePmtiles(it, oldTiles()) }
        installed.writeBytes(installed.readBytes().copyOf((installed.length() / 2).toInt()))
        val newTiles = newTiles()
        serveNew(newTiles)
        val mapSource = worldZ0to2(server.url("/planet.pmtiles").toString())

        val stats = PmtilesExtractor(alwaysDownloadMaxZoom = -1).extract(mapSource, outputFile, installed)

        assertFalse(stats.incremental)
        assertSameTiles(sourceFile, outputFile)
    }

    @Test
    fun `l'annullamento durante l'aggiornamento incrementale non ripiega sull'estrazione completa`() {
        val installed = File(sourceFile.parentFile, "installed.pmtiles").also { writePmtiles(it, oldTiles()) }
        val dispatcher = serveNew(newTiles())
        val mapSource = worldZ0to2(server.url("/planet.pmtiles").toString())

        try {
            PmtilesExtractor(alwaysDownloadMaxZoom = -1).extract(mapSource, outputFile, installed) { throw IllegalStateException("annullato") }
            fail("l'estrazione doveva essere interrotta")
        } catch (expected: IllegalStateException) {
            assertEquals("annullato", expected.message)
        }

        // solo header e root directory del Reader e header dell'indice: nessuna tile, nessun ripiego
        assertEquals(3, dispatcher.requestedStarts.size)
        assertFalse(outputFile.exists())
        assertTrue(outputFile.parentFile!!.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `la mappa leggera non ha l'ultimo zoom e lo dichiara nell'header`() {
        serveNew(oldTiles())
        val mapSource = worldZ0to2(server.url("/planet.pmtiles").toString())

        val stats = PmtilesExtractor().extract(mapSource, outputFile, detail = MapDetail.LIGHT)

        assertEquals(1, stats.maxZoom)
        assertEquals(5, stats.tilesDownloaded)
        Reader(outputFile).use {
            assertEquals(1, it.maxZoom.toInt())
            assertTrue(it.getTile(1, 1, 1) != null)
            assertNull(it.getTile(2, 0, 0))
        }
    }

    @Test
    fun `in automatico la mappa e' leggera solo oltre la soglia di peso`() {
        serveNew(oldTiles())
        val mapSource = worldZ0to2(server.url("/planet.pmtiles").toString())
        // 17 tile da 3 byte: 51 byte la mappa completa.
        val heavy = PmtilesExtractor(alwaysDownloadMaxZoom = 6, autoLightBytes = 40).extract(mapSource, outputFile, detail = MapDetail.AUTO)
        val light = File(sourceFile.parentFile, "light.pmtiles")
        val small = PmtilesExtractor(alwaysDownloadMaxZoom = 6, autoLightBytes = 100).extract(mapSource, light, detail = MapDetail.AUTO)

        assertEquals(1, heavy.maxZoom)
        assertEquals(2, small.maxZoom)
    }

    @Test
    fun `da leggera a dettagliata si scarica solo l'ultimo zoom`() {
        serveNew(oldTiles())
        val mapSource = worldZ0to2(server.url("/planet.pmtiles").toString())
        val installed = File(sourceFile.parentFile, "installed.pmtiles")
        PmtilesExtractor().extract(mapSource, installed, detail = MapDetail.LIGHT)

        val stats = PmtilesExtractor(alwaysDownloadMaxZoom = -1).extract(mapSource, outputFile, installed, detail = MapDetail.FULL)

        assertEquals(2, stats.maxZoom)
        assertEquals(12, stats.tilesDownloaded)
        assertEquals(5, stats.tilesReused)
    }
}
