package com.pockettravel.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialSourceTest {

    @Test
    fun `health topics point to the OMS source for any nationality`() {
        assertEquals("OMS — International Travel and Health", officialSourceFor(GuideCategory.SALUTE, "IT")?.name)
        assertEquals("OMS — International Travel and Health", officialSourceFor(GuideCategory.SALUTE, "JP")?.name)
    }

    @Test
    fun `customs topics point to the Agenzia delle Dogane source for Italians`() {
        assertEquals("Agenzia delle Dogane e dei Monopoli", officialSourceFor(GuideCategory.DOGANE, "IT")?.name)
    }

    @Test
    fun `other regulated topics point to the travel advice of the own country`() {
        assertEquals("Farnesina — Viaggiare Sicuri", officialSourceFor(GuideCategory.SICUREZZA, "IT")?.name)
        assertEquals("GOV.UK — Foreign travel advice", officialSourceFor(GuideCategory.ALLOGGIO, "GB")?.name)
        // Senza una fonte per quel paese, niente link invece di quello di un altro paese.
        assertNull(officialSourceFor(GuideCategory.SICUREZZA, "JP"))
    }

    @Test
    fun `national sources include the EU ones for EU citizens only`() {
        assertTrue(nationalOfficialSources("DE").any { it.name.startsWith("Your Europe") })
        assertTrue(nationalOfficialSources("US").none { it.name.startsWith("Your Europe") })
        assertTrue(nationalOfficialSources(null).isEmpty())
        assertTrue(globalOfficialSources.isNotEmpty() && globalOfficialSources.all { it.countries.isEmpty() })
    }
}
