package com.pockettravel.feature.map

import com.pockettravel.core.poi.PoiCategory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UsageModeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // Gli asset veri dell'app (i test JVM girano nella cartella del modulo), non una copia.
    private val assetProfiles = File("src/main/assets/brouter-profile")

    @Test
    fun `ogni modalita' mostra sempre le emergenze e nasconde il resto delle categorie`() {
        UsageMode.entries.forEach { mode ->
            assertTrue(mode.name, PoiCategory.OSPEDALE in mode.visibleCategories && PoiCategory.FARMACIA in mode.visibleCategories)
            assertEquals(mode.name, PoiCategory.entries.toSet(), mode.visibleCategories + mode.defaultHidden)
            assertTrue(mode.name, (mode.visibleCategories intersect mode.defaultHidden).isEmpty())
        }
    }

    @Test
    fun `i profili delle modalita' sono negli asset e instradano secondo il mezzo`() {
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
            val route = BRouterRouteEngine(segmentDir, assetProfiles, profile)
                .route(from = RoutePoint(45.5000, 9.9970), to = RoutePoint(45.5000, 10.0030))
            if (profile == "car-vario") {
                assertNull("in auto non si passa su una via pedonale", route)
            } else {
                assertNotNull("$profile deve trovare il percorso a piedi o in bici", route)
            }
        }
    }
}
