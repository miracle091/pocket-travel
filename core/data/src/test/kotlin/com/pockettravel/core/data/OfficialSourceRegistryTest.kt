package com.pockettravel.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coerenza del registro delle fonti ufficiali e casi limite delle funzioni che lo interrogano. */
class OfficialSourceRegistryTest {

    @Test
    fun `ogni fonte ha nome, indirizzo https e descrizione`() {
        officialSourcesRegistry.forEach { source ->
            assertTrue(source.name, source.name.isNotBlank())
            assertTrue(source.name, source.url.startsWith("https://"))
            assertFalse(source.name, source.url.any { it.isWhitespace() })
            assertTrue(source.name, source.description != 0)
        }
    }

    @Test
    fun `nomi e indirizzi delle fonti sono unici`() {
        val names = officialSourcesRegistry.map { it.name }
        val urls = officialSourcesRegistry.map { it.url }
        assertEquals(names.size, names.toSet().size)
        assertEquals(urls.size, urls.toSet().size)
    }

    @Test
    fun `i paesi delle fonti sono codici ISO alpha-2 maiuscoli`() {
        val codes = officialSourcesRegistry.flatMap { it.countries }
        assertTrue(codes.isNotEmpty())
        codes.forEach { assertTrue(it, it.matches(Regex("[A-Z]{2}"))) }
    }

    @Test
    fun `ogni cittadino dell'UE ha le fonti europee`() {
        val eu = officialSourcesRegistry.filter { it.name.startsWith("Your Europe") }
        assertEquals(2, eu.size)
        assertEquals(eu.first().countries, eu.last().countries)
        assertEquals(27, eu.first().countries.size)
        eu.first().countries.forEach { country ->
            assertTrue(country, nationalOfficialSources(country).containsAll(eu))
        }
    }

    @Test
    fun `i paesi dell'UE hanno un servizio di consigli di viaggio, anche condiviso`() {
        val withoutAdvice = officialSourcesRegistry.first { it.name.startsWith("Your Europe") }.countries
            .filter { travelAdviceSourceFor(it) == null }
        // Lussemburgo e Monaco rimandano alla Francia: l'elenco dei paesi UE senza servizio proprio e' noto e piccolo.
        assertTrue(withoutAdvice.toString(), withoutAdvice.size <= 5)
        assertEquals("France Diplomatie — Conseils aux voyageurs", travelAdviceSourceFor("LU")?.name)
        assertEquals("France Diplomatie — Conseils aux voyageurs", travelAdviceSourceFor("MC")?.name)
    }

    @Test
    fun `la fonte internazionale per la salute e' l'OMS, prima del CDC`() {
        assertEquals("OMS — International Travel and Health", officialSourceFor(GuideCategory.SALUTE, null)?.name)
        assertEquals(OfficialSourceTopic.HEALTH, globalOfficialSources.first().topic)
        assertTrue(globalOfficialSources.size >= 2)
    }

    @Test
    fun `senza nazionalita' solo la salute ha una fonte`() {
        GuideCategory.entries.filter { it != GuideCategory.SALUTE }.forEach {
            assertNull(it.name, officialSourceFor(it, null))
        }
    }

    @Test
    fun `le dogane ripiegano sui consigli di viaggio se il paese non ha una fonte doganale`() {
        assertEquals("Agenzia delle Dogane e dei Monopoli", officialSourceFor(GuideCategory.DOGANE, "IT")?.name)
        assertEquals("GOV.UK — Foreign travel advice", officialSourceFor(GuideCategory.DOGANE, "GB")?.name)
        assertNull(officialSourceFor(GuideCategory.DOGANE, "BR"))
    }

    @Test
    fun `il ripiego dei consigli di viaggio e' la fonte britannica`() {
        assertEquals(OfficialSourceTopic.TRAVEL_ADVICE, fallbackTravelAdviceSource.topic)
        assertTrue("GB" in fallbackTravelAdviceSource.countries)
    }

    @Test
    fun `Viaggiare Sicuri usa il codice alpha-3 della destinazione, in minuscolo o maiuscolo`() {
        val vs = checkNotNull(travelAdviceSourceFor("IT"))
        val expected = mapOf("FR" to "FRA", "de" to "DEU", "US" to "USA", "gb" to "GBR", "JP" to "JPN", "xk" to "KSV", "TH" to "THA")
        expected.forEach { (code, iso3) ->
            assertEquals(code, "https://www.viaggiaresicuri.it/find-country/country/$iso3", vs.urlFor(code))
        }
    }

    @Test
    fun `Viaggiare Sicuri senza pagina o con codici malformati rimanda alla pagina iniziale`() {
        val vs = checkNotNull(travelAdviceSourceFor("IT"))
        listOf("VA", "va", "GL", "FO", "SJ", "", " ", "12", "USA", "x", "!!", null).forEach {
            assertEquals(it.toString(), vs.url, vs.urlFor(it))
        }
    }

    @Test
    fun `le altre fonti non cambiano indirizzo con la destinazione`() {
        officialSourcesRegistry.filter { !it.url.startsWith("https://www.viaggiaresicuri.it") }.forEach { source ->
            assertEquals(source.name, source.url, source.urlFor("TH"))
            assertEquals(source.name, source.url, source.urlFor(null))
        }
    }
}
