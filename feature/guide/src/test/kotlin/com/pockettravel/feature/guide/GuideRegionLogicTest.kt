package com.pockettravel.feature.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

// Note sulle reti escluse e valuta dei fatti rapidi, per regione e per paese.
class GuideRegionLogicTest {

    @Test
    fun `la nota delle reti escluse sta nella guida delle regioni che le proporrebbero`() {
        assertEquals(listOf(R.string.transit_excluded_tmb), excludedTransitNotes("spagna"))
        assertEquals(listOf(R.string.transit_excluded_idfm), excludedTransitNotes("francia-ile-de-france"))
        assertEquals(listOf(R.string.transit_excluded_tcl), excludedTransitNotes("francia-alvernia-rodano-alpi"))
    }

    @Test
    fun `la Francia intera ha le note di Parigi e di Lione`() {
        assertEquals(listOf(R.string.transit_excluded_idfm, R.string.transit_excluded_tcl), excludedTransitNotes("francia"))
    }

    @Test
    fun `le altre regioni non hanno note sulle reti escluse`() {
        assertEquals(emptyList<Int>(), excludedTransitNotes("italia"))
        assertEquals(emptyList<Int>(), excludedTransitNotes("francia-bretagna"))
        assertEquals(emptyList<Int>(), excludedTransitNotes(""))
    }

    @Test
    fun `le note delle citta' ignorano maiuscole e minuscole`() {
        assertEquals(listOf(R.string.transit_excluded_idfm), excludedTransitCityNotes("PARIGI"))
        assertEquals(listOf(R.string.transit_excluded_tmb), excludedTransitCityNotes("barcelona"))
    }

    @Test
    fun `la valuta e' il nome nella lingua richiesta con il codice ISO`() {
        assertEquals("Japanese Yen (JPY)", currencyOf("JP", Locale.ENGLISH))
        assertTrue(currencyOf("IT", Locale.ENGLISH)?.endsWith("(EUR)") == true)
        assertNotNull(currencyOf("jp", Locale.ITALIAN))
    }

    @Test
    fun `senza una valuta nota la valuta manca`() {
        assertNull(currencyOf("ZZ", Locale.ENGLISH))
        assertNull(currencyOf("", Locale.ENGLISH))
    }
}
