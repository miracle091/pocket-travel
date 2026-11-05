package com.pockettravel.feature.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Partenza e destinazione su strade non collegate al resto della rete del modo, con un segmento vero: E5_N0.rd5 della
 * regione «Guinea Equatoriale - Bioko» come pubblicato (versione 2026.11.01.103.1: segmento di brouter.de ritagliato
 * sul rettangolo della regione allargato di 0,1 gradi con tools/data-pipeline/scripts/clip_rd5.py), dati
 * © OpenStreetMap contributors, ODbL 1.0 (licenza del file in bioko-segments/README.md). L'aeroporto di Malabo si aggancia a strade interne non collegate alla città, a piedi come in auto.
 */
class IslandRouteTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `BRouter da solo non arriva all'aeroporto di Malabo e non ne parte`() = runBlocking {
        for (profile in PROFILES) {
            val engine = engine(profile)

            val toAirport = engine.route(MALABO_CENTRE, MALABO_AIRPORT)
            val fromAirport = engine.route(MALABO_AIRPORT, MALABO_CENTRE)

            assertTrue("$profile: $toAirport", toAirport is RouteResult.Failed && "target island detected" in toAirport.message)
            assertTrue("$profile: $fromAirport", fromAirport is RouteResult.Failed && "start island detected" in fromAirport.message)
        }
    }

    @Test
    fun `verso l'aeroporto di Malabo il percorso arriva alla strada raggiungibile piu' vicina`() = runBlocking {
        for (profile in PROFILES) {
            val route = requireNotNull((IslandRetryRouteEngine(engine(profile)).route(MALABO_CENTRE, MALABO_AIRPORT) as? RouteResult.Found)?.route) { "$profile: nessun percorso" }

            assertEquals(0.0, route.startOffsetMeters, 0.0)
            assertTrue("$profile: ${route.endOffsetMeters} m", route.endOffsetMeters in 1.0..MAX_GAP_METERS)
        }
    }

    @Test
    fun `dall'aeroporto di Malabo il percorso parte dalla strada raggiungibile piu' vicina`() = runBlocking {
        for (profile in PROFILES) {
            val route = requireNotNull((IslandRetryRouteEngine(engine(profile)).route(MALABO_AIRPORT, MALABO_CENTRE) as? RouteResult.Found)?.route) { "$profile: nessun percorso" }

            assertTrue("$profile: ${route.startOffsetMeters} m", route.startOffsetMeters in 1.0..MAX_GAP_METERS)
            assertEquals(0.0, route.endOffsetMeters, 0.0)
        }
    }

    private fun engine(profile: String): BRouterRouteEngine {
        val profiles = tempFolder.newFolder().also { dir ->
            listOf("$profile.brf", "lookups.dat").forEach { File(ASSET_PROFILES, it).copyTo(File(dir, it)) }
        }
        return BRouterRouteEngine(segments(), profiles, profile, ioDispatcher = Dispatchers.IO, defaultDispatcher = Dispatchers.Default)
    }

    private fun segments(): File = tempFolder.newFolder().also { dir ->
        requireNotNull(javaClass.getResourceAsStream("/bioko-segments/E5_N0.rd5")) { "E5_N0.rd5 di Bioko mancante" }.use { input ->
            File(dir, "E5_N0.rd5").outputStream().use { input.copyTo(it) }
        }
    }

    private companion object {
        val ASSET_PROFILES = File("src/main/assets/brouter-profile")
        // Auto, bici e a piedi (UsageMode.routingProfile; escursionismo con hiking-mountain non provato).
        val PROFILES = listOf("car-vario", "trekking", "shortest")
        val MALABO_CENTRE = RoutePoint(3.7520, 8.7740)
        val MALABO_AIRPORT = RoutePoint(3.7553, 8.7087)
        // Punto spostato al piu' di 2 km, poi agganciato da BRouter alla strada entro il suo raggio di 250 m.
        const val MAX_GAP_METERS = IslandRetryRouteEngine.MAX_OFFSET_METERS + 250.0
    }
}
