package com.pockettravel.feature.map

import com.pockettravel.core.data.RoutingVariantChoice
import com.pockettravel.core.poi.PoiCategory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UsageModeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // Gli asset veri dell'app (i test JVM girano nella cartella del modulo), non una copia.
    private val assetProfiles = File("src/main/assets/brouter-profile")

    @Test
    fun `ogni modo mostra sempre le emergenze e nasconde il resto delle categorie`() {
        UsageMode.entries.forEach { mode ->
            assertTrue(mode.name, PoiCategory.OSPEDALE in mode.visibleCategories && PoiCategory.FARMACIA in mode.visibleCategories)
            assertEquals(mode.name, PoiCategory.entries.toSet(), mode.visibleCategories + mode.defaultHidden)
            assertTrue(mode.name, (mode.visibleCategories intersect mode.defaultHidden).isEmpty())
        }
    }

    @Test
    fun `gli internet cafe si vedono solo a piedi e con i mezzi pubblici`() {
        assertEquals(
            setOf(UsageMode.A_PIEDI, UsageMode.MEZZI_PUBBLICI),
            UsageMode.entries.filter { PoiCategory.INTERNET_CAFE in it.visibleCategories }.toSet(),
        )
    }

    @Test
    fun `gli orari dei mezzi si propongono a piedi, in bici, in escursione e coi mezzi, non in auto o camper`() {
        assertEquals(
            setOf(UsageMode.A_PIEDI, UsageMode.BICI, UsageMode.ESCURSIONISMO, UsageMode.MEZZI_PUBBLICI),
            UsageMode.entries.filterTo(mutableSetOf()) { it.proposesTransit },
        )
    }

    @Test
    fun `con piu' modi si vede l'unione delle categorie e si nasconde il resto`() {
        val modes = setOf(UsageMode.CAMPER, UsageMode.BICI)
        val visible = modes.visibleCategories()
        assertEquals(UsageMode.CAMPER.visibleCategories + UsageMode.BICI.visibleCategories, visible)
        assertTrue(PoiCategory.SERVIZI_CAMPER in visible && PoiCategory.RIPARAZIONE_BICI in visible)
        assertEquals(PoiCategory.entries.toSet(), visible + modes.defaultHidden())
        assertTrue((visible intersect modes.defaultHidden()).isEmpty())
        // Un solo modo: come prima.
        assertEquals(UsageMode.AUTO.defaultHidden, setOf(UsageMode.AUTO).defaultHidden())
    }

    @Test
    fun `senza modi scelti non si nasconde nessuna categoria`() {
        assertTrue(emptySet<UsageMode>().defaultHidden().isEmpty())
    }

    @Test
    fun `gli orari dei mezzi si propongono se almeno un modo li propone`() {
        assertTrue(setOf(UsageMode.CAMPER, UsageMode.A_PIEDI).proposesTransit())
        assertFalse(setOf(UsageMode.CAMPER, UsageMode.AUTO).proposesTransit())
        assertFalse(emptySet<UsageMode>().proposesTransit())
    }

    @Test
    fun `il profilo di default segue il mezzo iniziale`() {
        assertEquals("trekking", emptySet<UsageMode>().defaultRoutingProfile())
        assertEquals("car-vario", setOf(UsageMode.BICI, UsageMode.CAMPER).defaultRoutingProfile())
        assertEquals("trekking", setOf(UsageMode.BICI, UsageMode.MEZZI_PUBBLICI).defaultRoutingProfile())
        assertEquals("shortest", setOf(UsageMode.A_PIEDI, UsageMode.MEZZI_PUBBLICI).defaultRoutingProfile())
        assertEquals("hiking-mountain", setOf(UsageMode.ESCURSIONISMO).defaultRoutingProfile())
        // Con un solo modo e' il suo profilo, come prima della scelta multipla.
        UsageMode.entries.forEach { assertEquals(it.name, it.routingProfile, setOf(it).defaultRoutingProfile()) }
    }

    @Test
    fun `la rete stradale di default e' solo auto con auto e camper, completa senza mezzi a motore, tutta se modi misti o nessuno`() {
        assertEquals(RoutingVariantChoice.CAR, setOf(UsageMode.AUTO).routingDefault())
        assertEquals(RoutingVariantChoice.CAR, setOf(UsageMode.AUTO, UsageMode.CAMPER).routingDefault())
        assertEquals(RoutingVariantChoice.BIKE_FOOT, setOf(UsageMode.A_PIEDI, UsageMode.MEZZI_PUBBLICI).routingDefault())
        assertEquals(RoutingVariantChoice.BIKE_FOOT, setOf(UsageMode.BICI).routingDefault())
        assertEquals(RoutingVariantChoice.ALL, setOf(UsageMode.CAMPER, UsageMode.BICI).routingDefault())
        assertEquals(RoutingVariantChoice.ALL, emptySet<UsageMode>().routingDefault())
    }

    @Test
    fun `il modo singolo salvato diventa un insieme di un elemento`() {
        assertEquals(setOf(UsageMode.CAMPER), legacyUsageModes("CAMPER"))
        // "Con disabilita'" era un modo a parte con il profilo di A piedi.
        assertEquals(setOf(UsageMode.A_PIEDI), legacyUsageModes("ACCESSIBILITA"))
        assertTrue(legacyUsageModes("SCONOSCIUTA").isEmpty())
        assertTrue(legacyUsageModes(null).isEmpty())
        assertEquals(setOf(UsageMode.BICI, UsageMode.AUTO), usageModesFromNames(listOf("AUTO", "BICI", "SCONOSCIUTA")))
    }

    @Test
    fun `i profili dei modi sono negli asset e instradano secondo il mezzo`() {
        // Percorso di prova: un'unica via pedonale (highway=footway) a cavallo di due segmenti .rd5,
        // vedi BRouterRouteEngineTest.
        val segmentDir = tempFolder.newFolder("segments4")
        listOf("E5_N45.rd5", "E10_N45.rd5").forEach { name ->
            javaClass.getResourceAsStream("/multi-tile-segments/$name")!!.use { input ->
                File(segmentDir, name).outputStream().use { input.copyTo(it) }
            }
        }
        UsageMode.ROUTING_PROFILES.forEach { profile ->
            assertTrue("$profile.brf mancante negli asset", File(assetProfiles, "$profile.brf").isFile)
            val result = runBlocking {
                BRouterRouteEngine(segmentDir, assetProfiles, profile)
                    .route(from = RoutePoint(45.5000, 9.9970), to = RoutePoint(45.5000, 10.0030))
            }
            if (profile == "car-vario") {
                assertTrue("in auto non si passa su una via pedonale: $result", result !is RouteResult.Found)
            } else {
                assertTrue("$profile deve trovare il percorso a piedi o in bici: $result", result is RouteResult.Found)
            }
        }
    }
}
