package com.pockettravel.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class GenerateCountryFactsTest {
    private val lesotho = CountryFacts(
        capitalIt = "Maseru", capitalEn = "Maseru", currency = "LSL; ZAR", currencyIt = "loti; rand sudafricano",
        currencyEn = "loti; South African rand", driving = "left", callingCode = "+266", languagesIt = "sesotho; inglese",
        languagesEn = "Sesotho; English", timeZones = "UTC+02:00", plugs = "M", voltage = "220",
    )

    @Test
    fun `campi italiani in ordine con valori multipli e prese`() {
        assertEquals(
            mapOf(
                "Capitale" to "Maseru",
                "Lingua" to "Sesotho, inglese",
                "Elettricità" to "220 V, presa M",
                "Fuso orario" to "UTC+2",
                "Valuta" to "loti, rand sudafricano (LSL, ZAR)",
                "Prefisso telefonico" to "+266",
                "Lato di guida" to "sinistra",
            ).toList(),
            countryFactFields("ls", english = false, facts = lesotho).toList(),
        )
    }

    @Test
    fun `campi inglesi senza quelli assenti`() {
        val facts = lesotho.copy(capitalEn = "", plugs = "A, B", voltage = "127; 220", timeZones = "UTC-03:30; UTC+05:45", driving = "")
        assertEquals(
            mapOf(
                "Language" to "Sesotho, English",
                "Electricity" to "127/220 V, plug types A, B",
                "Time zone" to "UTC-3:30, UTC+5:45",
                "Currency" to "loti, South African rand (LSL, ZAR)",
                "Calling code" to "+266",
            ).toList(),
            countryFactFields("ls", english = true, facts = facts).toList(),
        )
        assertEquals(emptyMap<String, String>(), countryFactFields("regione-sconosciuta", english = true))
    }

    @Test
    fun `tsv con commenti e fusi abbreviati`() {
        val row = listOf("italia", "it", "Roma", "Rome", "EUR", "euro", "euro", "right", "+39", "italiano", "Italian", "UTC+01:00", "112", "C, F, L", "230")
        val facts = parseCountryFacts("# commento\n" + row.joinToString("\t") + "\n")
        assertEquals("Rome", facts.getValue("italia").capitalEn)
        assertEquals("C, F, L", facts.getValue("italia").plugs)
        assertEquals("UTC+0", shortUtcOffset("UTC+00:00"))
        assertEquals("UTC-9:30", shortUtcOffset("UTC-09:30"))
    }

    @Test
    fun `il tsv curato ha i dati delle regioni divise e le correzioni`() {
        val california = countryFactFields("stati-uniti-california", english = false)
        assertEquals("Sacramento", california["Capitale"])
        assertEquals("UTC-8", california["Fuso orario"])
        assertEquals("Inglese", california["Lingua"])
        assertEquals("UTC+5:30", countryFactFields("india", english = true)["Time zone"])
        assertEquals("UTC+0", countryFactFields("isole-canarie", english = false)["Fuso orario"])
    }
}
