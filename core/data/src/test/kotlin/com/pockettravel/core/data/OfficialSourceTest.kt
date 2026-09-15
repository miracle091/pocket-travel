package com.pockettravel.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OfficialSourceTest {

    @Test
    fun `health topics point to the OMS source`() {
        assertEquals("OMS — International Travel and Health", officialSourceFor(GuideCategory.SALUTE).name)
    }

    @Test
    fun `customs topics point to the Agenzia delle Dogane source`() {
        assertEquals("Agenzia delle Dogane e dei Monopoli", officialSourceFor(GuideCategory.DOGANE).name)
    }

    @Test
    fun `other regulated topics point to Farnesina`() {
        assertEquals("Farnesina — Viaggiare Sicuri", officialSourceFor(GuideCategory.SICUREZZA).name)
        assertEquals("Farnesina — Viaggiare Sicuri", officialSourceFor(GuideCategory.ALLOGGIO).name)
    }
}
