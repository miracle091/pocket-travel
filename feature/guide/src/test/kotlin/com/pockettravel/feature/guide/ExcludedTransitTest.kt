package com.pockettravel.feature.guide

import org.junit.Assert.assertEquals
import org.junit.Test

class ExcludedTransitTest {

    @Test
    fun `citta' delle reti escluse, con i titoli italiani e inglesi`() {
        assertEquals(listOf(R.string.transit_excluded_tmb), excludedTransitCityNotes("Barcellona"))
        assertEquals(listOf(R.string.transit_excluded_tmb), excludedTransitCityNotes("Barcelona"))
        assertEquals(listOf(R.string.transit_excluded_idfm), excludedTransitCityNotes("Parigi"))
        assertEquals(listOf(R.string.transit_excluded_idfm), excludedTransitCityNotes("Paris"))
        assertEquals(listOf(R.string.transit_excluded_tcl), excludedTransitCityNotes("Lione"))
        assertEquals(listOf(R.string.transit_excluded_tcl), excludedTransitCityNotes("Lyon"))
    }

    @Test
    fun `nessuna nota per le altre citta'`() {
        assertEquals(emptyList<Int>(), excludedTransitCityNotes("Madrid"))
        assertEquals(emptyList<Int>(), excludedTransitCityNotes("Marsiglia"))
    }
}
