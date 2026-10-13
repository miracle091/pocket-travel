package com.pockettravel.core.ui

import com.pockettravel.core.poi.PoiCategory
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoiLabelsAndCountryNameTest {

    @Test
    fun `ogni categoria ha un'etichetta propria`() {
        val labels = PoiCategory.entries.associateWith { it.label() }
        assertEquals(PoiCategory.entries.size, labels.values.toSet().size)
    }

    @Test
    fun `countryName da' il nome nella lingua richiesta`() {
        assertEquals("Italia", countryName("IT", Locale.ITALIAN))
        assertEquals("Italy", countryName("IT", Locale.ENGLISH))
        assertEquals("France", countryName("FR", Locale.ENGLISH))
    }

    @Test
    fun `countryName ignora le maiuscole del codice`() {
        assertEquals(countryName("IT", Locale.ENGLISH), countryName("it", Locale.ENGLISH))
    }

    @Test
    fun `countryName ritorna il codice se e' malformato`() {
        assertEquals("!!", countryName("!!", Locale.ENGLISH))
        assertEquals("ITALIA", countryName("ITALIA", Locale.ENGLISH))
    }

    @Test
    fun `countryName non ritorna mai un nome vuoto per un codice non vuoto`() {
        listOf("IT", "ZZ", "XK", "AQ", "1", "abc").forEach { assertTrue(it, countryName(it, Locale.ITALIAN).isNotBlank()) }
    }

    @Test
    fun `i paesi senza un nome nella lingua sono nel loro elenco ISO`() {
        // Tutti i codici ISO danno un nome o, in mancanza, il codice stesso.
        Locale.getISOCountries().forEach { assertTrue(it, countryName(it, Locale.ENGLISH).isNotBlank()) }
    }
}
