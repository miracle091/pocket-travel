package com.pockettravel.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Domini con lettere non ASCII (IDN), frequenti nei siti di OSM e Wikidata in Germania, Svizzera, Scandinavia.
class WebUrlIdnTest {

    @Test
    fun `un dominio con lettere accentate e' un indirizzo valido`() {
        assertTrue(isSafeWebUrl("https://münchen.de"))
        assertTrue(isSafeWebUrl("https://www.zürich.ch/de/besuch.html"))
    }

    @Test
    fun `un dominio con lettere accentate senza schema diventa https`() {
        assertEquals("https://www.göteborg.se", safeWebUrl("www.göteborg.se"))
    }

    @Test
    fun `gli schemi pericolosi restano scartati anche con lettere accentate`() {
        assertFalse(isSafeWebUrl("javascript://münchen.de/%0Aalert(1)"))
        assertFalse(isSafeWebUrl("intent://münchen.de#Intent;end"))
    }
}
