package com.pockettravel.pipeline

import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.RoutingParamCollector
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip reale: genera .rd5 da testdata/tiny-region.osm.xml con la pipeline BRouter
 * (OsmFastCutter -> PosUnifier -> WayLinker) e verifica che un motore
 * BRouter reale (stessa API di feature/map/BRouterRouteEngine.kt) instradi correttamente sul
 * segmento generato — lo stesso ciclo di vita che l'app dovra' fare dopo il download di un
 * pacchetto regionale.
 */
class GenerateRoutingGraphTest {

    @Test
    fun `il rd5 generato instrada correttamente`() {
        val osmFile = File("testdata/tiny-region.osm.xml")
        val outputDir = createTempDirectory("pocket-travel-test-rd5").toFile()

        try {
            val rd5Files = generateRd5(osmFile, outputDir)
            assertTrue("deve generare almeno un file .rd5", rd5Files.isNotEmpty())

            System.setProperty("segmentBaseDir", outputDir.path)
            System.setProperty("profileBaseDir", File("testdata/brouter-profile").path)

            // Stessa geometria di feature/map/src/test/kotlin/.../BRouterRouteEngineTest.kt:
            // la way "footway" del fixture va da (45.4642,9.1900) a (45.4658,9.1920).
            val lonlats = "9.1900,45.4642|9.1920,45.4658"
            val routingContext = RoutingContext()
            val paramCollector = RoutingParamCollector()
            val waypoints = paramCollector.getWayPointList(lonlats)
            val params = paramCollector.getUrlParams("lonlats=$lonlats&profile=trekking")
            paramCollector.setParams(routingContext, waypoints, params)

            val engine = RoutingEngine(null, null, outputDir, waypoints, routingContext)
            engine.doRun(60_000)

            assertNull("non deve esserci errore di routing: ${engine.errorMessage}", engine.errorMessage)
            val track = engine.foundTrack
            assertTrue("deve trovare un percorso con almeno un punto", track != null && track.nodes.isNotEmpty())
        } finally {
            outputDir.deleteRecursively()
        }
    }
}
