package com.pockettravel.app.regions

import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentRegionPreferencesTest {

    @Test
    fun `senza regioni aperte non c'e' un'ultima regione`() {
        assertNull(RecentRegionPreferences(InMemoryPreferencesFactory()).lastRegionId())
    }

    @Test
    fun `l'ultima regione resta per la prossima apertura`() {
        val files = InMemoryPreferencesFactory()
        RecentRegionPreferences(files).setLastRegionId("toscana")

        assertEquals("toscana", RecentRegionPreferences(files).lastRegionId())
    }

    @Test
    fun `una regione nuova sostituisce la precedente`() {
        val preferences = RecentRegionPreferences(InMemoryPreferencesFactory())
        preferences.setLastRegionId("toscana")
        preferences.setLastRegionId("umbria")

        assertEquals("umbria", preferences.lastRegionId())
    }

    @Test
    fun `l'id sta nel file recent_region con la chiave region_id`() {
        val files = InMemoryPreferencesFactory()
        RecentRegionPreferences(files).setLastRegionId("toscana")

        assertEquals("toscana", files.open("recent_region").getString("region_id", null))
    }
}
