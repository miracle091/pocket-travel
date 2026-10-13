package com.pockettravel.core.data.vaccination

import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VaccinationPreferencesTest {

    @Test
    fun `senza scelta non c'e' paese di partenza`() {
        assertNull(VaccinationPreferences(InMemoryPreferencesFactory()).departure)
    }

    @Test
    fun `il paese scelto resta per la prossima apertura`() {
        val files = InMemoryPreferencesFactory()
        VaccinationPreferences(files).departure = "IT"

        assertEquals("IT", VaccinationPreferences(files).departure)
    }

    @Test
    fun `null cancella la scelta`() {
        val preferences = VaccinationPreferences(InMemoryPreferencesFactory())
        preferences.departure = "FR"
        preferences.departure = null

        assertNull(preferences.departure)
    }
}
