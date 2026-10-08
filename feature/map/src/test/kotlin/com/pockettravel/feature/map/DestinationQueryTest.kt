package com.pockettravel.feature.map

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationQueryTest {

    private val names = listOf("Bar Stasera", "Colosseo")
    private val hasPois: suspend (String) -> Boolean = { text -> names.any { it.contains(text, ignoreCase = true) } }

    @Test
    fun `la meta col momento del giorno vince se e' il nome di un posto`() = runBlocking {
        assertEquals("bar stasera", destinationQuery("bar", "bar stasera", hasPois))
    }

    @Test
    fun `la meta col momento del giorno vale solo come parole intere del nome`() {
        assertTrue(containsWords("Bar Stasera", "bar stasera"))
        assertTrue(containsWords("Il Bar Stasera di Rimini", "bar stasera"))
        assertTrue(containsWords("Café Tomorrow", "cafe tomorrow"))
        assertFalse(containsWords("Station Nowy Świat", "station now"))
        assertFalse(containsWords("Beach Thistle Inn", "beach this"))
        assertFalse(containsWords("Debar Stasera", "bar stasera"))
    }

    @Test
    fun `altrimenti si cerca la meta senza il momento del giorno`() = runBlocking {
        assertEquals("Colosseo", destinationQuery("Colosseo", "Colosseo domani", hasPois))
        assertEquals("Colosseo", destinationQuery("Colosseo", null, hasPois))
    }
}
