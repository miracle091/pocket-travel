package com.pockettravel.feature.map

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.pockettravel.core.data.Rd5Merger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Verifica il wiring reale (RoutingContext/RoutingParamCollector/RoutingEngine di
 * :third-party:brouter-core, non un mock). Le fixture in src/test/resources/multi-tile-segments/
 * sono due .rd5 minuscoli con un'unica via; i percorsi reali (con svolte) usano un segmento vero
 * di brouter.de, troppo grande per il repo: quei test girano solo se RD5_CLIP_TEST_DIR indica una
 * cartella con orig/E10_N40.rd5 (da brouter.de) e clipped/E10_N40.rd5 (ritagliato sul riquadro di
 * San Marino con tools/data-pipeline/scripts/clip_rd5.py), altrimenti vengono saltati.
 */
class BRouterRouteEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `senza segmenti mancano i dati di percorso, senza eccezioni`() = runBlocking {
        val engine = BRouterRouteEngine(tempFolder.newFolder("segments4"), profileDir())

        val result = engine.route(from = RoutePoint(45.4642, 9.1900), to = RoutePoint(45.4658, 9.1920))

        assertEquals(RouteResult.NoRoutingData, result)
    }

    // Routing con 2+ segmenti .rd5
    // adiacenti nella stessa cartella. E5_N45.rd5/E10_N45.rd5 in
    // src/test/resources/multi-tile-segments/ sono stati generati una tantum con la pipeline
    // del progetto (tools/data-pipeline/routing, task generateRoutingGraph, rimosso il 2026-09-27:
    // resta nella storia git) da un estratto
    // sintetico con un'unica via che attraversa deliberatamente il confine di tile a lon=10.0
    // (tools/data-pipeline/testdata/tiny-region-two-tiles.osm.xml) — non file scaricati a mano,
    // e piccoli abbastanza (circa 4.4 KB l'uno) da bundlare come fixture permanenti, a differenza
    // dei segmenti reali di brouter.de usati solo per verifiche manuali una tantum.
    @Test
    fun `instrada correttamente quando il percorso attraversa due segmenti rd5 adiacenti`() = runBlocking {
        val engine = BRouterRouteEngine(twoTileSegments(), profileDir())
        // Nodo 1 e nodo 4 dell'estratto: il primo sta in E5_N45 (lon < 10), il secondo in
        // E10_N45 (lon > 10) — un percorso trovato dimostra che entrambi i segmenti sono stati
        // caricati e collegati correttamente al bordo, non solo il primo incontrato.
        val result = engine.route(from = RoutePoint(45.5000, 9.9970), to = RoutePoint(45.5000, 10.0030))

        assertTrue("un percorso che attraversa il confine di tile deve essere trovato: $result", result is RouteResult.Found)
        val route = (result as RouteResult.Found).route
        assertTrue("distanza attesa positiva e plausibile per ~0.006 gradi di longitudine", route.distanceMeters in 100.0..2000.0)
        assertEquals("l'ultima indicazione e' l'arrivo, sull'ultimo punto", TurnInstruction(TurnType.ARRIVE, 0.0, route.points.lastIndex), route.instructions.last().copy(distanceToNextMeters = 0.0))
    }

    // Due "regioni" che hanno ognuna meta' del dato: la A ha E5_N45 con la sua via e E10_N45 svuotata, la B
    // il contrario (stesso formato di un ritaglio che non tocca la tile). Da sole non instradano; con i
    // segmenti uniti (Rd5Merger) BRouter li legge e attraversa il confine fra le due parti, con lo
    // stesso percorso delle due tile intere e con la tile unita identica byte per byte all'originale.
    @Test
    fun `instrada attraverso il confine fra i segmenti di due regioni uniti`() = runBlocking {
        val whole = twoTileSegments()
        val regionA = regionRouting("a", "E5_N45.rd5" to File(whole, "E5_N45.rd5").readBytes(), "E10_N45.rd5" to emptied(File(whole, "E10_N45.rd5").readBytes()))
        val regionB = regionRouting("b", "E5_N45.rd5" to emptied(File(whole, "E5_N45.rd5").readBytes()), "E10_N45.rd5" to File(whole, "E10_N45.rd5").readBytes())
        val from = RoutePoint(45.5000, 9.9970)
        val to = RoutePoint(45.5000, 10.0030)
        val profiles = profileDir()

        val expected = BRouterRouteEngine(whole, profiles).route(from, to)
        assertTrue(expected is RouteResult.Found)
        assertNotEquals("una regione da sola non ha il percorso intero", expected, BRouterRouteEngine(regionA, profiles).route(from, to))
        assertNotEquals("una regione da sola non ha il percorso intero", expected, BRouterRouteEngine(regionB, profiles).route(from, to))
        val merged = Rd5Merger.mergedDirectory(tempFolder.newFolder(), listOf(regionA, regionB))
        val result = BRouterRouteEngine(merged, profiles).route(from, to)

        assertTrue("percorso con i segmenti uniti: $result", result is RouteResult.Found)
        assertEquals(expected, result)
        for (name in listOf("E5_N45.rd5", "E10_N45.rd5")) {
            assertArrayEquals(File(whole, name).readBytes(), File(merged, name).readBytes())
        }
    }

    // Con la cartella secondaria (regione piu' grande lasciata dov'e') le tile che mancano nella cartella
    // unita le trova BRouter li'.
    @Test
    fun `una tile presente solo nella regione lasciata dov'e' si trova nella cartella secondaria`() = runBlocking {
        val whole = twoTileSegments()
        val big = regionRouting("grande", "E5_N45.rd5" to File(whole, "E5_N45.rd5").readBytes(), "E10_N45.rd5" to File(whole, "E10_N45.rd5").readBytes())
        val small = regionRouting("piccola", "W5_N45.rd5" to emptied(File(whole, "E5_N45.rd5").readBytes()))

        val merged = Rd5Merger.mergedDirectory(tempFolder.newFolder(), listOf(small, big))
        val result = BRouterRouteEngine(merged, profileDir()).route(RoutePoint(45.5000, 9.9970), RoutePoint(45.5000, 10.0030))

        assertEquals(setOf("W5_N45.rd5", Rd5Merger.STORAGE_CONFIG_FILE), merged.list()!!.toSet())
        assertTrue("percorso con la cartella secondaria: $result", result is RouteResult.Found)
    }

    // Un ritaglio che non tocca la tile: stesse versioni e coda dell'originale, nessun blocco.
    private fun emptied(original: ByteArray): ByteArray {
        val source = ByteBuffer.wrap(original)
        val header = ByteBuffer.allocate(200)
        repeat(25) { header.putLong(((source.getLong() ushr 48) shl 48) or 200L) }
        val footerStart = (ByteBuffer.wrap(original).getLong(192) and 0xFFFFFFFFFFFFL).toInt()
        val footer = ByteBuffer.allocate(112)
        footer.putLong(ByteBuffer.wrap(original).getLong(footerStart))
        footer.putInt(crc(header.array()) xor 2) // divisor 32
        return header.array() + footer.array() + original.copyOfRange(footerStart + 112, original.size)
    }

    // regions/<id>/routing con i file dati, come RegionStorage.
    private fun regionRouting(regionId: String, vararg files: Pair<String, ByteArray>): File =
        File(tempFolder.root, "regions/$regionId/routing").also { dir ->
            dir.mkdirs()
            files.forEach { (name, bytes) -> File(dir, name).writeBytes(bytes) }
        }

    @Test
    fun `partenza fuori dai segmenti scaricati`() = runBlocking {
        val engine = BRouterRouteEngine(twoTileSegments(), profileDir())

        val result = engine.route(from = RoutePoint(41.9000, 12.5000), to = RoutePoint(45.5000, 10.0030))

        assertEquals(RouteResult.NoRoutingData, result)
    }

    @Test
    fun `il profilo in sedia a rotelle si legge e instrada, anche con allow_steps`() = runBlocking {
        val engine = BRouterRouteEngine(twoTileSegments(), profileDir("wheelchair.brf"))
        val from = RoutePoint(45.5000, 9.9970)
        val to = RoutePoint(45.5000, 10.0030)

        val strict = engine.route(from, to, profile = "wheelchair")
        val withSteps = engine.route(from, to, profile = "wheelchair", profileParams = mapOf("allow_steps" to "1"))

        assertTrue("profilo wheelchair: $strict", strict is RouteResult.Found)
        assertTrue("profilo wheelchair con allow_steps: $withSteps", withSteps is RouteResult.Found)
    }

    @Test
    fun `il profilo si cambia per il singolo percorso`() = runBlocking {
        val profiles = profileDir("car-vario.brf")
        val engine = BRouterRouteEngine(twoTileSegments(), profiles)
        val from = RoutePoint(45.5000, 9.9970)
        val to = RoutePoint(45.5000, 10.0030)

        assertTrue(engine.route(from, to) is RouteResult.Found)
        assertTrue("in auto non si passa sull'unica via, pedonale", engine.route(from, to, profile = "car-vario") !is RouteResult.Found)
    }

    @Test
    fun `un calcolo annullato libera il motore per il successivo`() = runBlocking {
        val engine = BRouterRouteEngine(twoTileSegments(), profileDir())
        val from = RoutePoint(45.5000, 9.9970)
        val to = RoutePoint(45.5000, 10.0030)

        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { engine.route(from, to) }
        cancelled.cancel()
        cancelled.join()

        val result = withTimeout(30_000) { engine.route(from, to) }
        assertTrue(result is RouteResult.Found)
    }

    // Segmenti ritagliati sulla regione (tools/data-pipeline/scripts/clip_rd5.py): lo stesso percorso
    // dentro la regione deve venire identico con il .rd5 originale di brouter.de e con quello ritagliato.
    @Test
    fun `un percorso dentro la regione e' identico con il segmento ritagliato`() = runBlocking {
        val base = realSegmentsDir()
        val profileDir = profileDir()

        val original = BRouterRouteEngine(File(base, "orig"), profileDir).route(SERRAVALLE, CITTA_DI_SAN_MARINO)
        val clipped = BRouterRouteEngine(File(base, "clipped"), profileDir).route(SERRAVALLE, CITTA_DI_SAN_MARINO)

        assertTrue("percorso con il segmento originale: $original", original is RouteResult.Found)
        assertEquals(original, clipped)
    }

    @Test
    fun `un percorso reale ha le svolte in ordine lungo il tracciato`() = runBlocking {
        val base = realSegmentsDir()

        val result = BRouterRouteEngine(File(base, "clipped"), profileDir()).route(SERRAVALLE, CITTA_DI_SAN_MARINO)

        val route = (result as RouteResult.Found).route
        val instructions = route.instructions
        assertTrue("attese svolte oltre l'arrivo: $instructions", instructions.count { it.type != TurnType.ARRIVE } > 0)
        assertEquals(TurnType.ARRIVE, instructions.last().type)
        assertTrue("indici crescenti dentro il tracciato", instructions.zipWithNext().all { (a, b) -> a.pointIndex <= b.pointIndex })
        assertTrue(instructions.all { it.pointIndex in route.points.indices && it.distanceToNextMeters >= 0.0 })
        assertTrue("uscita solo sulle rotonde", instructions.filter { !it.type.isRoundabout }.all { it.roundaboutExit == 0 })
        // Le distanze tra un'indicazione e la successiva coprono il percorso, tranne il tratto prima
        // della prima svolta.
        assertTrue(instructions.sumOf { it.distanceToNextMeters } in route.distanceMeters * 0.9..route.distanceMeters)
    }

    // Guida a sinistra: BRouter segna le rotonde percorse in senso orario (RNLB) con l'uscita negativa.
    // Segmento vero W5_N50.rd5 di brouter.de in RD5_UK_TEST_DIR, altrimenti saltato.
    @Test
    fun `rotonde del Regno Unito percorse a sinistra, con l'uscita`() = runBlocking {
        val dir = System.getenv("RD5_UK_TEST_DIR")?.let(::File)
        assumeTrue("RD5_UK_TEST_DIR non impostata", dir != null && File(dir, "W5_N50.rd5").exists())
        val engine = BRouterRouteEngine(dir!!, File("src/main/assets/brouter-profile"), "car-vario")

        // Milton Keynes, da Newport Pagnell a Kents Hill: una serie di rotonde.
        val route = (engine.route(RoutePoint(52.0870, -0.7222), RoutePoint(52.0240, -0.7070)) as RouteResult.Found).route

        val roundabouts = route.instructions.filter { it.type.isRoundabout }
        assertTrue("attese rotonde: ${route.instructions}", roundabouts.isNotEmpty())
        assertTrue(roundabouts.all { it.type == TurnType.ROUNDABOUT_LEFT && it.roundaboutExit > 0 })
    }

    private fun realSegmentsDir(): File {
        val base = System.getenv("RD5_CLIP_TEST_DIR")?.let(::File)
        assumeTrue("RD5_CLIP_TEST_DIR non impostata", base != null && File(base, "clipped/E10_N40.rd5").exists())
        return base!!
    }

    private fun crc(data: ByteArray): Int = (CRC32().apply { update(data) }.value xor 0xFFFFFFFFL).toInt()

    private fun twoTileSegments(): File = tempFolder.newFolder().also { dir ->
        copySegmentResource("E5_N45.rd5", dir)
        copySegmentResource("E10_N45.rd5", dir)
    }

    private fun profileDir(vararg extraAssetProfiles: String): File = tempFolder.newFolder().also {
        copyProfileResource("trekking.brf", it)
        copyProfileResource("lookups.dat", it)
        // Profili in piu' presi dagli asset veri dell'app (i test JVM girano nella cartella del modulo).
        extraAssetProfiles.forEach { name -> File("src/main/assets/brouter-profile", name).copyTo(File(it, name)) }
    }

    private fun copyProfileResource(name: String, targetDir: File) {
        javaClass.getResourceAsStream("/brouter-profile/$name")!!.use { input ->
            File(targetDir, name).outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun copySegmentResource(name: String, targetDir: File) {
        javaClass.getResourceAsStream("/multi-tile-segments/$name")!!.use { input ->
            File(targetDir, name).outputStream().use { output -> input.copyTo(output) }
        }
    }

    private companion object {
        // Serravalle -> Citta' di San Marino, a piedi (profilo trekking)
        val SERRAVALLE = RoutePoint(43.9690, 12.4800)
        val CITTA_DI_SAN_MARINO = RoutePoint(43.9356, 12.4473)
    }
}
