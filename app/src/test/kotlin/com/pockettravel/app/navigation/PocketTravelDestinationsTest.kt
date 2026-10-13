package com.pockettravel.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PocketTravelDestinationsTest {

    @Test
    fun `la scheda dell'hub regione di default e' la guida`() {
        assertEquals("guide", RegionHubRoute("italia").tab)
        assertEquals(RegionHubRoute("italia", tab = "guide"), RegionHubRoute("italia"))
    }

    @Test
    fun `l'anteprima e il browser hanno il titolo vuoto di default`() {
        assertEquals("", RegionPreviewRoute("italia").name)
        assertEquals("", InAppBrowserRoute("https://example.org").title)
    }

    @Test
    fun `le rotte con argomenti sono diverse se cambia un argomento`() {
        assertNotEquals(RegionHubRoute("italia"), RegionHubRoute("francia"))
        assertNotEquals(RegionHubRoute("italia", tab = "guide"), RegionHubRoute("italia", tab = "map"))
        assertNotEquals(InAppBrowserRoute("https://example.org", "A"), InAppBrowserRoute("https://example.org", "B"))
    }

    @Test
    fun `nessuna schermata richiesta all'avvio, e si azzera dopo l'apertura`() {
        OpenScreenRequests.screen.value = null
        assertNull(OpenScreenRequests.screen.value)

        OpenScreenRequests.screen.value = "updates"
        assertEquals("updates", OpenScreenRequests.screen.value)

        OpenScreenRequests.screen.value = null
        assertNull(OpenScreenRequests.screen.value)
    }
}
