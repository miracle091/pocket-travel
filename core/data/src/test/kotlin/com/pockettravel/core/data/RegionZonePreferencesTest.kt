package com.pockettravel.core.data

import androidx.core.content.edit
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionZonePreferencesTest {

    private val zone = RegionZone(7.0, 44.0, 8.0, 45.0)

    @Test
    fun `senza zone vale tutta la regione`() {
        val preferences = RegionZonePreferences(InMemoryPreferencesFactory())

        assertTrue(preferences.zones.value.isEmpty())
        assertNull(preferences.zone("italia"))
    }

    @Test
    fun `la zona scelta resta per la prossima apertura`() {
        val files = InMemoryPreferencesFactory()
        RegionZonePreferences(files).setZone("stati-uniti", zone)

        val reopened = RegionZonePreferences(files)

        assertEquals(zone, reopened.zone("stati-uniti"))
        assertNull(reopened.zone("francia"))
    }

    @Test
    fun `null torna a tutta la regione`() {
        val files = InMemoryPreferencesFactory()
        val preferences = RegionZonePreferences(files)
        preferences.setZone("italia", zone)
        preferences.setZone("italia", null)

        assertNull(preferences.zone("italia"))
        assertTrue(preferences.zones.value.isEmpty())
        assertTrue(RegionZonePreferences(files).zones.value.isEmpty())
    }

    @Test
    fun `legge il file region_zone salvato dall'app, una stringa per regione`() {
        val files = InMemoryPreferencesFactory()
        files.open("region_zone").edit { putString("italia", "7.0,44.0,8.0,45.0") }

        assertEquals(zone, RegionZonePreferences(files).zone("italia"))
    }

    @Test
    fun `i valori illeggibili o non validi si ignorano`() {
        val files = InMemoryPreferencesFactory()
        files.open("region_zone").edit {
            putString("rotta", "non,una,zona")
            putString("capovolta", "8.0,45.0,7.0,44.0")
            putString("corta", "1.0,2.0")
            putInt("intero", 3)
            putString("italia", "7.0,44.0,8.0,45.0")
        }

        assertEquals(mapOf("italia" to zone), RegionZonePreferences(files).zones.value)
    }
}
