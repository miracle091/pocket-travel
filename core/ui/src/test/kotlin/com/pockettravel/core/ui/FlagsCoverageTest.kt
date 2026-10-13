package com.pockettravel.core.ui

import androidx.compose.ui.Alignment
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlagsCoverageTest {

    @Test
    fun `i paesi piu' comuni hanno una bandiera`() {
        listOf("IT", "FR", "DE", "ES", "GB", "US", "JP", "CN", "BR", "AU", "CA", "CH", "AT", "PT", "GR", "XK", "EU")
            .forEach { assertNotNull(it, flagRes(it)) }
    }

    @Test
    fun `ogni bandiera trovata ha una risorsa diversa`() {
        val codes = Locale.getISOCountries().filter { flagRes(it) != null }
        val ids = codes.map { flagRes(it) }
        assertEquals(codes.size, ids.toSet().size)
        assertTrue(codes.size > 200)
    }

    @Test
    fun `i codici ISO senza bandiera sono pochi`() {
        val missing = Locale.getISOCountries().filter { flagRes(it) == null }
        assertTrue(missing.toString(), missing.size <= 10)
    }

    @Test
    fun `i codici con ritaglio particolare hanno una bandiera`() {
        Locale.getISOCountries()
            .filter { flagCropAlignment(it) != Alignment.Center }
            .forEach { assertNotNull(it, flagRes(it)) }
    }

    @Test
    fun `i codici non alfabetici o con spazi non hanno bandiera ne ritaglio`() {
        listOf("1", "12", "i t", "it ", "İT", "--").forEach {
            assertNull(it, flagRes(it))
            assertEquals(it, Alignment.Center, flagCropAlignment(it))
        }
    }

    @Test
    fun `i territori e i paesi con segno su un lato sono ritagliati verso quel lato`() {
        assertEquals(Alignment.CenterStart, flagCropAlignment("cn"))
        assertEquals(Alignment.CenterEnd, flagCropAlignment("nz"))
        assertEquals(Alignment.CenterEnd, flagCropAlignment("ZM"))
    }
}
