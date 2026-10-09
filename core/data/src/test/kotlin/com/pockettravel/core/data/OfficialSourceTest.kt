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
        assertNull(officialSourceFor(GuideCategory.SICUREZZA, "BR"))
    }

    @Test
    fun `every country has at most one travel advice source`() {
        val advice = officialSourcesRegistry.filter { it.topic == OfficialSourceTopic.TRAVEL_ADVICE }
        assertTrue(advice.all { it.countries.isNotEmpty() && it.url.startsWith("https://") })
        val countries = advice.flatMap { it.countries }
        assertEquals(countries.size, countries.toSet().size)
        // Microstati che rimandano al servizio di un vicino.
        assertEquals("Farnesina — Viaggiare Sicuri", travelAdviceSourceFor("SM")?.name)
        assertEquals("DFAE — Consigli di viaggio", travelAdviceSourceFor("LI")?.name)
        assertEquals("Farnesina — Viaggiare Sicuri", travelAdviceSourceFor("IT")?.name)
        assertEquals("外務省 海外安全ホームページ", travelAdviceSourceFor("JP")?.name)
        assertNull(travelAdviceSourceFor("BR"))
        assertNull(travelAdviceSourceFor(null))
        assertEquals("GOV.UK — Foreign travel advice", fallbackTravelAdviceSource.name)
    }

    @Test
    fun `Viaggiare Sicuri links the country page of the destination`() {
        val vs = checkNotNull(travelAdviceSourceFor("IT"))
        assertEquals("https://www.viaggiaresicuri.it/find-country/country/THA", vs.urlFor("th"))
        assertEquals("https://www.viaggiaresicuri.it/find-country/country/KSV", vs.urlFor("XK"))
        // Senza pagina sul sito (territori, Italia) o senza codice ISO alpha-3 (Canarie), o senza destinazione: la pagina iniziale.
        listOf("GL", "it", "IC", null).forEach { assertEquals("https://www.viaggiaresicuri.it", vs.urlFor(it)) }
        // Gli altri ministeri non hanno indirizzi per paese nel registro: resta il loro indirizzo.
        assertEquals(fallbackTravelAdviceSource.url, fallbackTravelAdviceSource.urlFor("TH"))
    }

    @Test
    fun `national sources include the EU ones for EU citizens only`() {
        assertTrue(nationalOfficialSources("DE").any { it.name.startsWith("Your Europe") })
        assertTrue(nationalOfficialSources("US").none { it.name.startsWith("Your Europe") })
        assertTrue(nationalOfficialSources(null).isEmpty())
        assertTrue(globalOfficialSources.isNotEmpty() && globalOfficialSources.all { it.countries.isEmpty() })
    }
}
