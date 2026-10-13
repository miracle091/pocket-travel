package com.pockettravel.core.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Casi limite di Rd5Merger: controlli sugli argomenti, emisferi nei nomi delle tessere, griglie miste, file troncati,
 * code e cartelle di cache. I segmenti si costruiscono con uno scrittore indipendente, come in Rd5MergerTest.
 */
class Rd5MergerEdgeCasesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val cellA = byteArrayOf(1, 2, 3, 4, 5)
    private val cellB = byteArrayOf(9, 8, 7)
    private val cellC = byteArrayOf(42, 43, 44, 45, 46, 47, 48, 49)

    private val noLinks = Rd5Merger.Links { _, _, _, _ -> emptyList() }

    @Test
    fun `senza segmenti non c'e' niente da unire`() {
        assertThrows(IllegalArgumentException::class.java) {
            Rd5Merger.merge(emptyList(), File(tempFolder.root, "E10_N45.rd5"), noLinks)
        }
    }

    @Test
    fun `senza cartelle di regioni non crea niente nella cache`() {
        val cache = File(tempFolder.root, "cache")

        val error = assertThrows(IllegalArgumentException::class.java) { Rd5Merger.mergedDirectory(cache, emptyList(), noLinks) }

        assertTrue(error.message.orEmpty().contains("cartella"))
        assertFalse(cache.exists())
    }

    @Test
    fun `un segmento solo si copia identico e sostituisce il file di destinazione`() {
        val source = write("fonte.rd5", rd5(100, mapOf((3 to 7) to cellA, (20 to 900) to cellB)))
        val target = write("E10_N45.rd5", byteArrayOf(0, 1, 2))

        Rd5Merger.merge(listOf(source), target, noLinks)

        assertArrayEquals(source.readBytes(), target.readBytes())
    }

    @Test
    fun `un segmento solo si copia anche con un nome che non e' di una tile`() {
        val source = write("fonte.rd5", rd5(100, mapOf((3 to 7) to cellA)))
        val target = File(tempFolder.root, "qualunque.rd5")

        Rd5Merger.merge(listOf(source), target, noLinks)

        assertArrayEquals(source.readBytes(), target.readBytes())
    }

    @Test
    fun `due copie dello stesso segmento si uniscono in un segmento identico`() {
        val bytes = rd5(100, mapOf((0 to 1) to cellA, (0 to 2) to cellB, (7 to 400) to cellC))
        val first = write("uno.rd5", bytes)
        val second = write("due.rd5", bytes)
        val target = File(tempFolder.root, "E10_N45.rd5")

        Rd5Merger.merge(listOf(first, second), target, noLinks)

        assertArrayEquals(bytes, target.readBytes())
    }

    @Test
    fun `i nomi di tile a ovest e a sud si leggono`() {
        val first = write("uno.rd5", rd5(100, mapOf((0 to 5) to cellA, (12 to 3) to cellC)))
        val second = write("due.rd5", rd5(150, mapOf((0 to 900) to cellB)))

        for (name in listOf("W10_S45.rd5", "E0_N0.rd5", "W5_N40.rd5", "E10_S5.rd5")) {
            val target = File(tempFolder.root, name)
            Rd5Merger.merge(listOf(first, second), target, noLinks)
            assertArrayEquals(name, rd5(150, mapOf((0 to 5) to cellA, (0 to 900) to cellB, (12 to 3) to cellC)), target.readBytes())
        }
    }

    @Test
    fun `un nome che non e' di una tile non si unisce`() {
        val first = write("uno.rd5", rd5(100, mapOf((0 to 1) to cellA)))
        val second = write("due.rd5", rd5(100, mapOf((0 to 2) to cellB)))

        val error = assertThrows(IOException::class.java) {
            Rd5Merger.merge(listOf(first, second), File(tempFolder.root, "mappa.rd5"), noLinks)
        }

        assertTrue(error.message.orEmpty().contains("mappa.rd5"))
    }

    @Test
    fun `segmenti con griglie diverse non si uniscono`() {
        val coarse = write("grossa.rd5", rd5(100, mapOf((0 to 1) to cellA), divisor = 32))
        val fine = write("fine.rd5", rd5(100, mapOf((0 to 1) to cellB), divisor = 80))

        val error = assertThrows(IOException::class.java) {
            Rd5Merger.merge(listOf(coarse, fine), File(tempFolder.root, "E10_N45.rd5"), noLinks)
        }

        assertTrue(error.message.orEmpty().contains("divisori"))
    }

    @Test
    fun `un file piu' corto dell'indice o senza la coda e' rifiutato con il suo nome`() {
        val good = write("buono.rd5", rd5(100, mapOf((0 to 1) to cellA)))
        val short = write("corto.rd5", ByteArray(50))
        val full = rd5(100, mapOf((0 to 2) to cellB))
        // Intestazione e blocchi sono interi; il piede (112 byte piu' la coda di 1 byte) e' tagliato a 50 byte.
        val noFooter = write("senzacoda.rd5", full.copyOf(full.size - 113 + 50))

        val tooShort = assertThrows(IOException::class.java) {
            Rd5Merger.merge(listOf(good, short), File(tempFolder.root, "E10_N45.rd5"), noLinks)
        }
        assertTrue(tooShort.message.orEmpty().contains("corto.rd5"))

        val cut = assertThrows(IOException::class.java) {
            Rd5Merger.merge(listOf(good, noFooter), File(tempFolder.root, "E10_N45.rd5"), noLinks)
        }
        assertTrue(cut.message.orEmpty().contains("senzacoda.rd5"))
    }

    @Test
    fun `la coda in fondo al file unito e' quella del segmento piu' recente`() {
        val old = write("vecchio.rd5", rd5(100, mapOf((0 to 5) to cellA), tail = byteArrayOf(1)))
        val recent = write("recente.rd5", rd5(150, mapOf((12 to 3) to cellC), tail = byteArrayOf(2, 2)))
        val target = File(tempFolder.root, "E10_N45.rd5")

        Rd5Merger.merge(listOf(old, recent), target, noLinks)

        assertArrayEquals(rd5(150, mapOf((0 to 5) to cellA, (12 to 3) to cellC), tail = byteArrayOf(2, 2)), target.readBytes())
    }

    @Test
    fun `una regione sola resta dov'e' e la cartella unita ha solo la configurazione`() {
        val italy = routingDir("italia", "E10_N40.rd5" to rd5(100, mapOf((0 to 1) to cellA)))
        val cache = File(tempFolder.root, "cache")

        val merged = Rd5Merger.mergedDirectory(cache, listOf(italy), noLinks)

        assertEquals(setOf(Rd5Merger.STORAGE_CONFIG_FILE), merged.list()!!.toSet())
        val secondaryPath = File(merged, Rd5Merger.STORAGE_CONFIG_FILE).readText().trim().removePrefix("secondary_segment_dir=")
        assertEquals(italy.canonicalFile, File(merged, secondaryPath).canonicalFile)
    }

    @Test
    fun `nelle cartelle contano solo i file rd5`() {
        val big = routingDir("grande", "E10_N45.rd5" to rd5(100, mapOf((0 to 1) to cellA, (1 to 1) to cellB)))
        val small = routingDir("piccola", "E15_N45.rd5" to rd5(200, mapOf((0 to 1) to cellC)))
        File(small, "leggimi.txt").writeText("non e' un segmento")
        File(small, "E20_N45.rd5.tmp").writeBytes(ByteArray(10))
        File(small, "E25_N45.rd5").mkdirs()

        val merged = Rd5Merger.mergedDirectory(File(tempFolder.root, "cache"), listOf(big, small), noLinks)

        assertEquals(setOf("E15_N45.rd5", Rd5Merger.STORAGE_CONFIG_FILE), merged.list()!!.toSet())
        assertArrayEquals(File(small, "E15_N45.rd5").readBytes(), File(merged, "E15_N45.rd5").readBytes())
        assertFalse(File(merged, "leggimi.txt").exists())
    }

    @Test
    fun `un segmento rovinato non lascia cartelle a meta' nella cache`() {
        val big = routingDir("grande", "E10_N40.rd5" to rd5(100, mapOf((0 to 1) to cellA, (1 to 1) to cellB)))
        val small = routingDir("piccola", "E10_N40.rd5" to ByteArray(30))
        val cache = File(tempFolder.root, "cache")

        assertThrows(IOException::class.java) { Rd5Merger.mergedDirectory(cache, listOf(big, small), noLinks) }

        assertEquals(emptyList<String>(), cache.list()?.toList().orEmpty())
    }

    private fun write(name: String, bytes: ByteArray): File = File(tempFolder.root, name).also { it.writeBytes(bytes) }

    // routing/ di una regione: <root>/regions/<regionId>/routing, come lo dispone RegionStorage.
    private fun routingDir(regionId: String, vararg files: Pair<String, ByteArray>): File =
        File(tempFolder.root, "regions/$regionId/routing").also { dir ->
            dir.mkdirs()
            files.forEach { (name, bytes) -> File(dir, name).writeBytes(bytes) }
        }

    /**
     * Un .rd5 nel formato scritto dallo script di ritaglio: [cells] = (sotto-tessera, microcella) -> byte, con i CRC
     * dell'indice dei blocchi e dell'intestazione calcolati senza usare Rd5Merger.
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
