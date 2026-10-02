package com.pockettravel.app.regions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionZoneDialogTest {

    @Test
    fun `la zona si misura in km alla latitudine media`() {
        // 1 grado a 60 gradi nord: ~56 km in larghezza, ~111 in altezza.
        assertEquals(56 to 111, boundsSizeKm(10.0, 59.5, 11.0, 60.5))
    }

    @Test
    fun `solo i paesi grandi propongono una zona`() {
        assertTrue(RegionBbox(6.6, 35.5, 18.5, 47.1).isLarge()) // Italia
        assertFalse(RegionBbox(20.9, 55.6, 28.3, 58.1).isLarge()) // Lettonia
        assertFalse(RegionBbox(12.4, 43.89, 12.52, 43.99).isLarge()) // San Marino
    }
}
