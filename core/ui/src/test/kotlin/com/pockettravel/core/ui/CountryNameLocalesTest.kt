package com.pockettravel.core.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CountryNameLocalesTest {

    @Test
    fun `countryName cambia con la lingua`() {
        assertEquals("Italien", countryName("IT", Locale.GERMAN))
        assertEquals("Italie", countryName("IT", Locale.FRENCH))
        assertEquals("Deutschland", countryName("DE", Locale.GERMAN))
        assertNotEquals(countryName("DE", Locale.ITALIAN), countryName("DE", Locale.ENGLISH))
    }

    @Test
    fun `countryName italiano per i paesi piu' comuni`() {
        assertEquals("Francia", countryName("FR", Locale.ITALIAN))
        assertEquals("Germania", countryName("DE", Locale.ITALIAN))
        assertEquals("Spagna", countryName("ES", Locale.ITALIAN))
        assertEquals("Stati Uniti", countryName("US", Locale.ITALIAN))
    }

    @Test
    fun `countryName con codice vuoto ritorna stringa vuota`() {
        assertEquals("", countryName("", Locale.ENGLISH))
    }

    @Test
    fun `countryName con codici troppo lunghi o con simboli ritorna il codice`() {
        listOf("ITA", "I", "?", "I T").forEach { assertEquals(it, countryName(it, Locale.ITALIAN)) }
    }

    @Test
    fun `countryName ritorna sempre il nome esteso e non il codice per i paesi ISO`() {
        // Con le lingue di sistema un nome uguale al codice non e' ammesso per questi paesi.
        listOf("IT", "FR", "DE", "ES", "GB", "US", "JP").forEach {
            assertNotEquals(it, countryName(it, Locale.ENGLISH))
            assertNotEquals(it, countryName(it, Locale.ITALIAN))
        }
    }
}
