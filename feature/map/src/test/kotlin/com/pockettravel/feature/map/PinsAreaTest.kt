package com.pockettravel.feature.map

import com.pockettravel.core.data.RegionZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinsAreaTest {

    @Test
    fun `la vista dentro la zona scaricata resta quella`() {
        val view = MapBounds(12.0, 41.0, 13.0, 42.0)
        assertEquals(view, pinsArea(view, zone = null))
        assertEquals(MapBounds(12.5, 41.0, 13.0, 41.5), pinsArea(view, RegionZone(12.5, 40.0, 14.0, 41.5)))
    }

    @Test
    fun `una vista a cavallo dei 180 gradi prende tutta la fascia di latitudine`() {
        // Ovest oltre est (Nuova Zelanda vista sopra le Chatham), oppure longitudini oltre 180.
        assertEquals(MapBounds(-180.0, -46.0, 180.0, -43.0), pinsArea(MapBounds(178.0, -46.0, -177.0, -43.0), zone = null))
        assertEquals(MapBounds(-180.0, 60.0, 180.0, 66.0), pinsArea(MapBounds(178.0, 60.0, 182.0, 66.0), zone = null))
    }

    @Test
    fun `fuori dalla zona scaricata nessuna area`() {
        assertNull(pinsArea(MapBounds(12.0, 41.0, 13.0, 42.0), RegionZone(14.0, 41.0, 15.0, 42.0)))
    }
}
