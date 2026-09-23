package com.pockettravel.feature.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verifica il wiring reale (RoutingContext/RoutingParamCollector/RoutingEngine di
 * :third-party:brouter-core, non un mock) senza dati di segmento reali: costruire un .rd5 di
 * prova richiederebbe btools.mapcreator, non ancora reverse-ingegnerizzato
 * — l'unico modo per verificare un percorso reale oggi è un segmento scaricato da brouter.de,
 * usato solo per una verifica manuale una tantum, non bundlato qui per non
 * appesantire il repo con un file di 12 MB.
 *
 * Questo test copre comunque codice di produzione reale: caricamento profilo (.brf/lookups.dat)
 * e costruzione del RoutingContext, con un esito atteso pulito (nessun percorso trovato, nessuna eccezione) quando
 * la cartella dei segmenti è vuota.
 */
class BRouterRouteEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `nessun segmento disponibile restituisce null senza eccezioni`() {
        val segmentDir = tempFolder.newFolder("segments4")
        val profileDir = tempFolder.newFolder("profiles2")
        copyProfileResource("trekking.brf", profileDir)
        copyProfileResource("lookups.dat", profileDir)

        val engine = BRouterRouteEngine(segmentDir, profileDir)
        val route = engine.route(
            from = RoutePoint(45.4642, 9.1900),
            to = RoutePoint(45.4658, 9.1920),
        )

        assertNull("senza segmenti non deve essere trovato nessun percorso", route)
    }

    // Gap segnalato nel log di sviluppo: il routing non era mai stato verificato con 2+ segmenti .rd5
    // adiacenti nella stessa cartella. E5_N45.rd5/E10_N45.rd5 in
    // src/test/resources/multi-tile-segments/ sono stati generati una tantum con la pipeline
    // reale del progetto (tools/data-pipeline/routing, task generateRoutingGraph) da un estratto
    // sintetico con un'unica via che attraversa deliberatamente il confine di tile a lon=10.0
    // (tools/data-pipeline/testdata/tiny-region-two-tiles.osm.xml) — non file scaricati a mano,
    // e piccoli abbastanza (circa 4.4 KB l'uno) da bundlare come fixture permanenti, a differenza
    // dei segmenti reali di brouter.de usati solo per verifiche manuali una tantum.
    @Test
    fun `instrada correttamente quando il percorso attraversa due segmenti rd5 adiacenti`() {
        val segmentDir = tempFolder.newFolder("segments4")
        val profileDir = tempFolder.newFolder("profiles2")
        copyProfileResource("trekking.brf", profileDir)
        copyProfileResource("lookups.dat", profileDir)
        copySegmentResource("E5_N45.rd5", segmentDir)
        copySegmentResource("E10_N45.rd5", segmentDir)

        val engine = BRouterRouteEngine(segmentDir, profileDir)
        // Nodo 1 e nodo 4 dell'estratto: il primo sta in E5_N45 (lon < 10), il secondo in
        // E10_N45 (lon > 10) — un percorso trovato dimostra che entrambi i segmenti sono stati
        // caricati e collegati correttamente al bordo, non solo il primo incontrato.
        val route = engine.route(
            from = RoutePoint(45.5000, 9.9970),
            to = RoutePoint(45.5000, 10.0030),
        )

        assertNotNull("un percorso che attraversa il confine di tile deve essere trovato usando entrambi i segmenti", route)
        assertTrue("distanza attesa positiva e plausibile per ~0.006 gradi di longitudine", route!!.distanceMeters in 100.0..2000.0)
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
}
