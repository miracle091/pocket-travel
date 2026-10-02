package com.pockettravel.pipeline

import btools.codec.Rd5CarFilter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FilterRd5Test {

    @get:Rule
    val temp = TemporaryFolder()

    // Piccolo segmento di prova (lo stesso dei test di BRouterRouteEngine) e il lookups.dat dei profili dell'app.
    private val tile = File("testdata/E10_N45-tiny.rd5")
    private val lookups = File("../../feature/map/src/main/assets/brouter-profile/lookups.dat")

    @Test
    fun `senza filtro la riscrittura e' identica all'originale`() {
        val out = temp.newFile("all.rd5")
        Rd5CarFilter.rewrite(lookups, tile, out, false)
        assertArrayEquals(tile.readBytes(), out.readBytes())
    }

    @Test
    fun `la variante auto si rilegge e non e' piu' grande`() {
        val car = temp.newFile("car.rd5")
        Rd5CarFilter.rewrite(lookups, tile, car, true)
        assertTrue(car.length() in 1..tile.length())
        // Rileggerla senza filtro la riscrive uguale: indici e CRC sono validi per il lettore di BRouter.
        val again = temp.newFile("again.rd5")
        Rd5CarFilter.rewrite(lookups, car, again, false)
        assertArrayEquals(car.readBytes(), again.readBytes())
    }

    @Test
    fun `strade per l'auto si', sentieri e marciapiedi no`() {
        assertTrue(Rd5CarFilter.carAccepts(mapOf("highway" to "residential")))
        assertTrue(Rd5CarFilter.carAccepts(mapOf("highway" to "service", "access" to "private", "motorcar" to "destination")))
        assertTrue(Rd5CarFilter.carAccepts(mapOf("route" to "ferry")))
        assertTrue(Rd5CarFilter.carAccepts(mapOf("highway" to "track", "tracktype" to "grade1")))
        assertTrue(Rd5CarFilter.carAccepts(mapOf("highway" to "track", "access" to "yes")))
        assertFalse(Rd5CarFilter.carAccepts(mapOf("highway" to "footway")))
        assertFalse(Rd5CarFilter.carAccepts(mapOf("highway" to "path")))
        assertFalse(Rd5CarFilter.carAccepts(mapOf("highway" to "track", "tracktype" to "grade3")))
        assertFalse(Rd5CarFilter.carAccepts(mapOf("highway" to "residential", "motor_vehicle" to "no")))
        assertFalse(Rd5CarFilter.carAccepts(mapOf("highway" to "pedestrian")))
    }
}
