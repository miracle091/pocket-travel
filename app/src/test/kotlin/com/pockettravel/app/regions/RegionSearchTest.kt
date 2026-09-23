package com.pockettravel.app.regions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionSearchTest {
    @Test
    fun `la ricerca ignora maiuscole, accenti e spazi ai bordi`() {
        assertTrue(matchesQuery("San Marino", "marino"))
        assertTrue(matchesQuery("São Tomé e Príncipe", "sao tome"))
        assertTrue(matchesQuery("Stati Uniti - Alaska", "  ALASKA "))
    }

    @Test
    fun `query vuota mostra tutto, query senza corrispondenza nulla`() {
        assertTrue(matchesQuery("Italia", ""))
        assertFalse(matchesQuery("Italia", "giappone"))
    }
}
