package com.pockettravel.pipeline

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.io.path.createTempDirectory

class MapFingerprintTest {

    // Fixture reale: le tile z15 del centro di San Marino (build Protomaps).
    private val fixture = File("testdata/san-marino-center-z15.pmtiles")

    @Test
    fun `l'impronta conta le tile del rettangolo ed e' stabile`() {
        val first = mapFingerprint(fileRangeReader(fixture), 12.40, 43.89, 12.52, 43.99, 15, 15)
        val second = mapFingerprint(fileRangeReader(fixture), 12.40, 43.89, 12.52, 43.99, 15, 15)

        assertTrue(first.tiles > 0)
        assertEquals(first, second)
        assertEquals(64, first.sha256.length)
    }

    @Test
    fun `un rettangolo senza tile da' un'impronta diversa`() {
        val sanMarino = mapFingerprint(fileRangeReader(fixture), 12.40, 43.89, 12.52, 43.99, 15, 15)
        val elsewhere = mapFingerprint(fileRangeReader(fixture), 0.0, 0.0, 0.01, 0.01, 15, 15)

        assertEquals(0, elsewhere.tiles)
        assertNotEquals(sanMarino.sha256, elsewhere.sha256)
    }

    @Test
    fun `un id per ogni tile del rettangolo, ordinati`() {
        // A z0 e z1 un rettangolo piccolo cade in una sola tile per livello.
        val ids = tileIdsIn(12.44, 43.92, 12.46, 43.94, 0, 1)

        assertEquals(2, ids.size)
        assertEquals(ids.sorted(), ids.toList())
    }

    @Test
    fun `gli id delle tile di tutto il mondo coprono gli intervalli contigui dei livelli di zoom`() {
        // Gli id PMTiles sono contigui per livello: z0 = 0, z1 = 1..4, z2 = 5..20.
        val ids = tileIdsIn(-180.0, -90.0, 180.0, 90.0, 0, 2)

        assertEquals((0L..20L).toList(), ids.toList())
    }

    @Test
    fun `un rettangolo ridotto a un punto occupa una sola tile per livello`() {
        val ids = tileIdsIn(12.45, 43.94, 12.45, 43.94, 5, 7)

        assertEquals(3, ids.size)
        assertEquals(3, ids.toSet().size)
    }

    @Test
    fun `gli id non hanno duplicati e il conteggio cresce con il rettangolo`() {
        val small = tileIdsIn(12.40, 43.89, 12.52, 43.99, 12, 12)
        val large = tileIdsIn(10.0, 42.0, 14.0, 45.0, 12, 12)

        assertEquals(small.size, small.toSet().size)
        assertTrue(large.size > small.size)
        assertTrue(large.toSet().containsAll(small.toSet()))
    }

    @Test
    fun `longitudini e latitudini fuori intervallo sono riportate ai bordi della mappa`() {
        val clipped = tileIdsIn(-400.0, -100.0, 400.0, 100.0, 0, 3)
        val world = tileIdsIn(-180.0, -85.0511, 180.0, 85.0511, 0, 3)

        assertEquals(world.toList(), clipped.toList())
        assertEquals(1 + 4 + 16 + 64, clipped.size)
    }

    @Test
    fun `con minZoom maggiore di maxZoom non ci sono tile`() {
        assertEquals(0, tileIdsIn(12.4, 43.9, 12.5, 44.0, 5, 4).size)
    }

    @Test
    fun `un'impronta su un rettangolo piu' piccolo non ha piu' tile di quella sul rettangolo intero`() {
        val full = mapFingerprint(fileRangeReader(fixture), 12.40, 43.89, 12.52, 43.99, 15, 15)
        val part = mapFingerprint(fileRangeReader(fixture), 12.44, 43.92, 12.46, 43.94, 15, 15)

        assertTrue(part.tiles <= full.tiles)
        if (part.tiles != full.tiles) assertNotEquals(full.sha256, part.sha256)
    }

    @Test
    fun `l'impronta senza tile e' lo SHA-256 dell'input vuoto`() {
        val empty = mapFingerprint(fileRangeReader(fixture), 0.0, 0.0, 0.01, 0.01, 15, 15)

        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", empty.sha256)
    }

    @Test
    fun `l'errore del lettore si propaga senza produrre un'impronta`() {
        val failing = RangeReader { _, _ -> throw java.io.IOException("rete assente") }

        assertThrows(java.io.IOException::class.java) { mapFingerprint(failing, 12.40, 43.89, 12.52, 43.99, 15, 15) }
    }

    @Test
    fun `fileRangeReader legge l'intervallo chiesto e tronca oltre la fine del file`() {
        val dir = createTempDirectory("pocket-travel-test-range").toFile()
        try {
            val file = File(dir, "dati.bin").apply { writeBytes(ByteArray(100) { it.toByte() }) }
            val reader = fileRangeReader(file)

            assertEquals(listOf<Byte>(10, 11, 12), reader.read(10, 3).toList())
            assertEquals(10, reader.read(90, 500).size)
            assertEquals(99.toByte(), reader.read(90, 500).last())
            assertEquals(100, reader.read(0, 100).size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `l'impronta della fixture letta da file coincide con quella letta via HTTP a intervalli`() {
        val data = fixture.readBytes()
        val ranges = mutableListOf<String>()
        val userAgents = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/mappa.pmtiles") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            ranges += range.orEmpty()
            userAgents += exchange.requestHeaders.getFirst("User-Agent").orEmpty()
            val (from, to) = requireNotNull(Regex("bytes=(\\d+)-(\\d+)").matchEntire(range.orEmpty())) { "Range inatteso: $range" }.destructured
            val end = minOf(to.toInt(), data.size - 1)
            val body = data.copyOfRange(from.toInt(), end + 1)
            exchange.sendResponseHeaders(206, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/mappa.pmtiles"
            val viaHttp = mapFingerprint(httpRangeReader(url), 12.40, 43.89, 12.52, 43.99, 15, 15)
            val viaFile = mapFingerprint(fileRangeReader(fixture), 12.40, 43.89, 12.52, 43.99, 15, 15)

            assertEquals(viaFile, viaHttp)
            assertEquals("bytes=0-126", ranges.first())
            assertTrue(ranges.all { it.startsWith("bytes=") })
            assertTrue(userAgents.all { it.contains("pocket-travel") })
        } finally {
            server.stop(0)
        }
    }
}
