package com.pockettravel.core.ui

import com.pockettravel.core.data.alerts.NationalEventType
import com.pockettravel.core.data.alerts.NationalHazard
import com.pockettravel.core.data.alerts.WarningColor
import com.pockettravel.core.data.alerts.WeatherHazard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlertTextTest {

    @Test
    fun `il livello del vulcano si legge dal codice evento di GeoNet`() {
        assertEquals(3, volcanoLevel("VAL3"))
        assertNull(volcanoLevel("VALd"))
        assertNull(volcanoLevel("EQ"))
    }

    // getValue lancerebbe nel worker delle notifiche per una voce nuova senza testo.
    @Test
    fun `ogni voce degli enum delle allerte ha il suo testo`() {
        assertEquals(WarningColor.entries.toSet(), COLOR_NAMES.keys)
        assertEquals(WeatherHazard.entries.toSet(), HAZARD_NAMES.keys)
        assertEquals(NationalHazard.entries.toSet(), NATIONAL_HAZARDS.keys)
        assertEquals(NationalEventType.entries.toSet(), NATIONAL_TYPES.keys)
    }
}
