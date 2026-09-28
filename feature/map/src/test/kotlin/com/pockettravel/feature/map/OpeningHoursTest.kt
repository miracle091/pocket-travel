package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Test

class OpeningHoursTest {

    @Test
    fun `giorni in italiano, una regola per riga e chiuso al posto di off`() {
        assertEquals(
            "lun-sab 12:00-15:00, 19:00-23:00\ndom chiuso",
            formatOpeningHours("Mo-Sa 12:00-15:00,19:00-23:00; Su off"),
        )
    }

    @Test
    fun `sempre aperto e festivi`() {
        assertEquals("sempre aperto", formatOpeningHours("24/7"))
        assertEquals("lun-ven 08:00-20:00\nfestivi chiuso", formatOpeningHours("Mo-Fr 08:00-20:00; PH closed"))
    }

    @Test
    fun `quello che non riconosce resta com'e'`() {
        // "Mon" non e' un giorno OSM valido: non si tocca (nemmeno il "Mo" che contiene).
        assertEquals("Mon 10:00-12:00", formatOpeningHours("Mon 10:00-12:00"))
        assertEquals("sunrise-sunset", formatOpeningHours("sunrise-sunset"))
    }
}
