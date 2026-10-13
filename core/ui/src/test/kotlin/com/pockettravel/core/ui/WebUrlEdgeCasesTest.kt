package com.pockettravel.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUrlEdgeCasesTest {

    @Test
    fun `isSafeWebUrl ignora gli spazi attorno all'indirizzo`() {
        assertTrue(isSafeWebUrl("  https://esempio.it  "))
        assertTrue(isSafeWebUrl("\thttp://esempio.it\n"))
    }

    @Test
    fun `isSafeWebUrl accetta porta, credenziali, percorso e frammento`() {
        assertTrue(isSafeWebUrl("https://esempio.it:8080/a/b?x=1#top"))
        assertTrue(isSafeWebUrl("https://utente:segreto@esempio.it/"))
        assertTrue(isSafeWebUrl("http://127.0.0.1:3000"))
        assertTrue(isSafeWebUrl("http://[::1]/"))
    }

    @Test
    fun `isSafeWebUrl scarta schemi misti a http`() {
        listOf("httpx://esempio.it", "http+evil://esempio.it", "xhttps://esempio.it", "javascript://esempio.it/%0Aalert(1)")
            .forEach { assertFalse(it, isSafeWebUrl(it)) }
    }

    @Test
    fun `isSafeWebUrl scarta gli indirizzi opachi e quelli con caratteri non validi`() {
        listOf("https:esempio.it", "https://esempio.it/a b", "https://esempio.it/<x>", "https://esempio.it/\"x\"")
            .forEach { assertFalse(it, isSafeWebUrl(it)) }
    }

    @Test
    fun `safeWebUrl toglie gli spazi e tiene lo schema esistente`() {
        assertEquals("https://esempio.it", safeWebUrl("  https://esempio.it "))
        assertEquals("HTTP://esempio.it", safeWebUrl("HTTP://esempio.it"))
    }

    @Test
    fun `safeWebUrl aggiunge https a host con percorso e query`() {
        assertEquals("https://esempio.it/menu?lang=it", safeWebUrl("esempio.it/menu?lang=it"))
        assertEquals("https://esempio.it", safeWebUrl("esempio.it"))
    }

    @Test
    fun `safeWebUrl scarta vuoti e solo spazi`() {
        assertNull(safeWebUrl(""))
        assertNull(safeWebUrl("    "))
    }

    @Test
    fun `safeWebUrl scarta ogni altro schema anche con maiuscole`() {
        assertNull(safeWebUrl("JavaScript:alert(1)"))
        assertNull(safeWebUrl("tel:+390123456"))
        assertNull(safeWebUrl("mailto:info@esempio.it"))
        assertNull(safeWebUrl("ftp://esempio.it"))
        assertNull(safeWebUrl("content://com.esempio/x"))
    }

    @Test
    fun `safeWebUrl scarta un indirizzo senza schema con due punti`() {
        // Un ':' prima del percorso (anche una porta) fa scartare l'indirizzo: non si indovina lo schema.
        assertNull(safeWebUrl("esempio.it:8080"))
    }

    @Test
    fun `safeWebUrl scarta un indirizzo con host mancante`() {
        assertNull(safeWebUrl("https://"))
        assertNull(safeWebUrl("//esempio.it"))
    }

    @Test
    fun `safeWebUrl accetta due punti dopo l'inizio del percorso, della query o del frammento`() {
        assertEquals("https://www.esempio.it/p?x=a:b", safeWebUrl("www.esempio.it/p?x=a:b"))
        assertEquals("https://esempio.it?x=a:b", safeWebUrl("esempio.it?x=a:b"))
        assertEquals("https://esempio.it#a:b", safeWebUrl("esempio.it#a:b"))
        assertEquals("https://esempio.it/p/a:b", safeWebUrl("esempio.it/p/a:b"))
    }
}
