package com.pockettravel.core.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Le micro-celle sono byte qualsiasi (l'unione non ne guarda il contenuto): i file sono costruiti qui
 * con un writer indipendente dal Rd5Merger, e il risultato dell'unione dev'essere identico byte per
 * byte al file che contiene direttamente le micro-celle attese, indici e CRC compresi. La lettura
 * con BRouter e' in BRouterRouteEngineTest (feature/map).
 */
class Rd5MergerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val cellA = byteArrayOf(1, 2, 3, 4, 5)
    private val cellB = byteArrayOf(9, 8, 7)
    private val cellC = byteArrayOf(42, 43, 44, 45, 46, 47, 48, 49)

    // Nessuna strada fra micro-celle lontane: restano le vicine sulla griglia.
    private val noLinks = Rd5Merger.Links { _, _, _, _ -> emptyList() }

    /** Chiave di [cell] della sotto-tile [sub] della tile [TILE] (E10_N45: 190 e 135 gradi da -180/-90), divisor 32. */
    private fun key(sub: Int, cell: Int) = Rd5Merger.cellKey((190 + sub / 5) * 32 + cell % 32, (135 + sub % 5) * 32 + cell / 32)

    @Test
    fun `l'unione di due ritagli della stessa tile ha le micro-celle di entrambi, con indici e CRC validi`() {
        for (divisor in listOf(32, 80)) {
            val first = write("primo.rd5", rd5(100, mapOf((0 to 5) to cellA, (0 to 6) to cellB, (12 to 3) to cellC), divisor))
            val second = write("secondo.rd5", rd5(100, mapOf((0 to 900) to cellB, (12 to 3) to cellC, (24 to 1) to cellA), divisor))
            val target = File(tempFolder.root, TILE)

            Rd5Merger.merge(listOf(first, second), target, noLinks)

            val expected = rd5(100, mapOf((0 to 5) to cellA, (0 to 6) to cellB, (0 to 900) to cellB, (12 to 3) to cellC, (24 to 1) to cellA), divisor)
            assertArrayEquals("divisor $divisor", expected, target.readBytes())
        }
    }

    @Test
    fun `una micro-cella presente in due file e' quella del file piu' recente, in qualsiasi ordine`() {
        val old = write("vecchio.rd5", rd5(100, mapOf((3 to 7) to cellA, (3 to 500) to cellB)))
        val recent = write("recente.rd5", rd5(200, mapOf((3 to 7) to cellC)))

        for (order in listOf(listOf(old, recent), listOf(recent, old))) {
            val target = File(tempFolder.root, TILE)
            Rd5Merger.merge(order, target, noLinks)
            // La micro-cella 7 e' del recente, la 500 (lontana) c'e' solo nel vecchio; creationTime = il piu' recente.
            assertArrayEquals(rd5(200, mapOf((3 to 7) to cellC, (3 to 500) to cellB)), target.readBytes())
        }
    }

    // Micro-celle 5..8 sulla stessa riga della sotto-tile 3. La 8 c'e' solo nel vecchio: la 7 del recente, diversa
    // nel vecchio, potrebbe non combaciare con lei e passa al vecchio; poi la 6, per lo stesso motivo. La 5 c'e'
    // solo nel recente: il bordo fra 5 e 6 resta (nessuna delle due e' uguale nei due file).
    @Test
    fun `al bordo fra i due file la micro-cella cambiata del recente passa al vecchio`() {
        val old = write("vecchio.rd5", rd5(100, mapOf((3 to 6) to cellA, (3 to 7) to cellA, (3 to 8) to cellB)))
        val recent = write("recente.rd5", rd5(200, mapOf((3 to 5) to cellC, (3 to 6) to cellC, (3 to 7) to cellC)))
        val target = File(tempFolder.root, TILE)

        Rd5Merger.merge(listOf(old, recent), target, noLinks)

        assertArrayEquals(rd5(200, mapOf((3 to 5) to cellC, (3 to 6) to cellA, (3 to 7) to cellA, (3 to 8) to cellB)), target.readBytes())
    }

    @Test
    fun `una micro-cella uguale nei due file ferma il bordo`() {
        val old = write("vecchio.rd5", rd5(100, mapOf((3 to 6) to cellA, (3 to 7) to cellB, (3 to 8) to cellB)))
        val recent = write("recente.rd5", rd5(200, mapOf((3 to 6) to cellC, (3 to 7) to cellB)))
        val target = File(tempFolder.root, TILE)

        Rd5Merger.merge(listOf(old, recent), target, noLinks)

        // La 7 e' uguale nei due file, quindi combacia con la 8 del vecchio: la 6 resta del recente.
        assertArrayEquals(rd5(200, mapOf((3 to 6) to cellC, (3 to 7) to cellB, (3 to 8) to cellB)), target.readBytes())
    }

    @Test
    fun `il bordo si sposta anche fra due sotto-tile vicine`() {
        // Sotto-tile 3 e 8: un grado di longitudine in piu'. L'ultima colonna (31) della 3 confina con la prima (0) della 8.
        val old = write("vecchio.rd5", rd5(100, mapOf((3 to 31) to cellA, (8 to 0) to cellB)))
        val recent = write("recente.rd5", rd5(200, mapOf((3 to 31) to cellC)))
        val target = File(tempFolder.root, TILE)

        Rd5Merger.merge(listOf(old, recent), target, noLinks)

        assertArrayEquals(rd5(200, mapOf((3 to 31) to cellA, (8 to 0) to cellB)), target.readBytes())
    }

    // Una strada lunga (autostrada, traghetto) unisce micro-celle lontane: il bordo si controlla anche li'.
    // La strada c'e' solo nella build del vecchio, il file piu' grande: nella sua versione della 7 (cellA),
    // non in quella del recente. Si leggono tutte le versioni delle micro-celle presenti in piu' file.
    @Test
    fun `il bordo si sposta anche fra micro-celle lontane unite da una strada`() {
        val old = write("vecchio.rd5", rd5(100, mapOf((3 to 7) to cellA, (3 to 500) to cellC)))
        val recent = write("recente.rd5", rd5(200, mapOf((3 to 7) to cellB)))
        val road = Rd5Merger.Links { lonIdx, latIdx, _, bytes ->
            if (Rd5Merger.cellKey(lonIdx, latIdx) == key(3, 7) && bytes.contentEquals(cellA)) listOf(key(3, 500)) else emptyList()
        }
        val target = File(tempFolder.root, TILE)

        Rd5Merger.merge(listOf(old, recent), target, noLinks)
        assertArrayEquals("senza la strada le due micro-celle non si toccano", rd5(200, mapOf((3 to 7) to cellB, (3 to 500) to cellC)), target.readBytes())

        Rd5Merger.merge(listOf(old, recent), target, road)
        assertArrayEquals(rd5(200, mapOf((3 to 7) to cellA, (3 to 500) to cellC)), target.readBytes())
    }

    // E10_N45 e E15_N45 si toccano: l'ultima colonna (sotto-tile 20, micro-cella 31) della prima confina con la
    // prima (sotto-tile 0, micro-cella 0) della seconda. La seconda c'e' solo nella regione grande, che resta dov'e'.
    @Test
    fun `il bordo si sposta anche fra due tile diverse`() {
        val big = routingDir("grande", "E10_N45.rd5" to rd5(100, mapOf((20 to 31) to cellA)), "E15_N45.rd5" to rd5(100, mapOf((0 to 0) to cellB)))
        val small = routingDir("piccola", "E10_N45.rd5" to rd5(200, mapOf((20 to 31) to cellC)))

        val merged = Rd5Merger.mergedDirectory(File(tempFolder.root, "cache"), listOf(small, big), noLinks)

        assertEquals(setOf("E10_N45.rd5", Rd5Merger.STORAGE_CONFIG_FILE), merged.list()!!.toSet())
        assertArrayEquals(rd5(200, mapOf((20 to 31) to cellA)), File(merged, "E10_N45.rd5").readBytes())
    }

    @Test
    fun `una sotto-tile con un'altra versione dei lookup non si mescola con quella piu' recente`() {
        val old = write("vecchio.rd5", rd5(100, mapOf((3 to 7) to cellA, (4 to 1) to cellB), version = { 10 }))
        val recent = write("recente.rd5", rd5(200, mapOf((3 to 8) to cellC)))
        val target = File(tempFolder.root, TILE)

        Rd5Merger.merge(listOf(old, recent), target, noLinks)

        // Sotto-tile 3: solo il recente (versione 11); sotto-tile 4: c'e' solo nel vecchio, resta con la sua (10).
        assertArrayEquals(rd5(200, mapOf((3 to 8) to cellC, (4 to 1) to cellB), version = { if (it == 4) 10 else 11 }), target.readBytes())
    }

    @Test
    fun `un file rovinato o senza coda con i CRC non si unisce`() {
        val good = write("buono.rd5", rd5(100, mapOf((0 to 1) to cellA)))
        val bytes = rd5(100, mapOf((0 to 2) to cellB))
        bytes[10] = (bytes[10] + 1).toByte() // indice di testa modificato: il CRC non torna
        val corrupt = write("rovinato.rd5", bytes)

        try {
            Rd5Merger.merge(listOf(good, corrupt), File(tempFolder.root, TILE), noLinks)
            fail("attesa IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("rovinato.rd5"))
        }
    }

    @Test
    fun `la regione piu' grande resta com'e' (cartella secondaria), delle altre si copiano le tile che non ha`() {
        val italy = routingDir("italia", "E10_N40.rd5" to rd5(100, mapOf((0 to 1) to cellA)), "E10_N45.rd5" to rd5(100, mapOf((1 to 1) to cellB, (5 to 1) to cellB)))
        val marino = routingDir("san-marino", "E10_N40.rd5" to rd5(150, mapOf((0 to 2) to cellC)), "W5_N40.rd5" to rd5(150, mapOf((2 to 2) to cellC)))
        val cache = File(tempFolder.root, "cache")

        val merged = Rd5Merger.mergedDirectory(cache, listOf(marino, italy), noLinks)

        // E10_N45 c'e' solo in Italia: la trova BRouter nella cartella secondaria.
        assertEquals(setOf("E10_N40.rd5", "W5_N40.rd5", Rd5Merger.STORAGE_CONFIG_FILE), merged.list()!!.toSet())
        assertArrayEquals(rd5(150, mapOf((0 to 1) to cellA, (0 to 2) to cellC)), File(merged, "E10_N40.rd5").readBytes())
        assertArrayEquals(File(marino, "W5_N40.rd5").readBytes(), File(merged, "W5_N40.rd5").readBytes())
        val secondaryPath = File(merged, Rd5Merger.STORAGE_CONFIG_FILE).readText().trim().removePrefix("secondary_segment_dir=")
        assertEquals(italy.canonicalFile, File(merged, secondaryPath).canonicalFile)
    }

    @Test
    fun `la cartella unita si rifa' solo quando cambia una versione, le vecchie si cancellano`() {
        val italy = routingDir("italia", "E10_N40.rd5" to rd5(100, mapOf((0 to 1) to cellA)))
        val marino = routingDir("san-marino", "E10_N40.rd5" to rd5(150, mapOf((0 to 2) to cellC)))
        val cache = File(tempFolder.root, "cache")

        val first = Rd5Merger.mergedDirectory(cache, listOf(italy, marino), noLinks)
        File(first, "segno").writeText("non rifatta")
        val again = Rd5Merger.mergedDirectory(cache, listOf(italy, marino), noLinks)
        assertEquals(first, again)
        assertTrue("stessa cartella riusata", File(again, "segno").exists())

        // Marino rigenerata: file nuovo, quindi dimensione o data diverse.
        File(marino, "E10_N40.rd5").writeBytes(rd5(300, mapOf((0 to 2) to cellB)))
        val rebuilt = Rd5Merger.mergedDirectory(cache, listOf(italy, marino), noLinks)

        assertFalse(first == rebuilt)
        assertTrue("l'ultima combinazione resta, un calcolo in corso potrebbe leggerla", first.exists())
        assertArrayEquals(rd5(300, mapOf((0 to 1) to cellA, (0 to 2) to cellB)), File(rebuilt, "E10_N40.rd5").readBytes())

        File(marino, "E10_N40.rd5").writeBytes(rd5(400, mapOf((0 to 2) to cellC)))
        val third = Rd5Merger.mergedDirectory(cache, listOf(italy, marino), noLinks)
        assertFalse("le combinazioni piu' vecchie si cancellano", first.exists())
        assertEquals(setOf(rebuilt.name, third.name), cache.list()!!.toSet())

        // Cache svuotata in parte dal sistema: senza storageconfig.txt si rifa'.
        File(third, Rd5Merger.STORAGE_CONFIG_FILE).delete()
        assertTrue(File(Rd5Merger.mergedDirectory(cache, listOf(italy, marino), noLinks), Rd5Merger.STORAGE_CONFIG_FILE).isFile)
    }

    private companion object {
        // Il file unito ha il nome della tile: da li' le coordinate delle micro-celle.
        const val TILE = "E10_N45.rd5"
    }

    private fun write(name: String, bytes: ByteArray): File = File(tempFolder.root, name).also { it.writeBytes(bytes) }

    // routing/ di una regione: <root>/<regionId>/routing, come RegionStorage.
    private fun routingDir(regionId: String, vararg files: Pair<String, ByteArray>): File =
        File(tempFolder.root, "regions/$regionId/routing").also { dir ->
            dir.mkdirs()
            files.forEach { (name, bytes) -> File(dir, name).writeBytes(bytes) }
        }

    /**
     * Un .rd5 col formato di clip_rd5.py: [cells] = (sotto-tile, micro-cella) -> byte. Indici, CRC dei
     * blocchi e dell'indice come li scrive quello script.
     */
    private fun rd5(
        creationTime: Long,
        cells: Map<Pair<Int, Int>, ByteArray>,
        divisor: Int = 32,
        version: (Int) -> Long = { 11 },
        tail: ByteArray = byteArrayOf(3),
    ): ByteArray {
        val indexSize = divisor * divisor * 4
        val blocks = (0 until 25).map { sub ->
            val own = cells.filterKeys { it.first == sub }.mapKeys { it.key.second }
            if (own.isEmpty()) return@map ByteArray(0) to 0
            val index = ByteBuffer.allocate(indexSize)
            var size = indexSize
            val data = java.io.ByteArrayOutputStream()
            for (cell in 0 until divisor * divisor) {
                own[cell]?.let { data.write(it); size += it.size }
                index.putInt(size)
            }
            (index.array() + data.toByteArray()) to crc(index.array())
        }
        val header = ByteBuffer.allocate(200)
        var position = 200L
        blocks.forEachIndexed { sub, (block, _) -> position += block.size; header.putLong((version(sub) shl 48) or position) }
        val footer = ByteBuffer.allocate(112)
        footer.putLong(creationTime)
        footer.putInt(crc(header.array()) xor (if (divisor == 32) 2 else 0))
        blocks.forEach { (_, blockCrc) -> footer.putInt(blockCrc) }
        return header.array() + blocks.fold(ByteArray(0)) { all, (block, _) -> all + block } + footer.array() + tail
    }

    private fun crc(data: ByteArray): Int = (CRC32().apply { update(data) }.value xor 0xFFFFFFFFL).toInt()
}
