package com.pockettravel.app.regions

import com.pockettravel.app.navigation.RegionHubRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class RegionTabTest {

    @Test
    fun `ogni chiave apre la sua scheda`() {
        RegionTab.entries.forEach { assertEquals(it, RegionTab.fromKey(it.key)) }
    }

    @Test
    fun `le chiavi delle schede sono distinte`() {
        assertEquals(RegionTab.entries.size, RegionTab.entries.map { it.key }.toSet().size)
    }

    @Test
    fun `una chiave sconosciuta, vuota o con maiuscole apre la guida`() {
        listOf("sconosciuta", "", " map", "MAP", "Guide").forEach { assertEquals(it, RegionTab.GUIDE, RegionTab.fromKey(it)) }
    }

    @Test
    fun `la rotta senza scheda apre la guida`() {
        assertEquals(RegionTab.GUIDE, RegionTab.fromKey(RegionHubRoute("it").tab))
    }

    // PocketTravelNavHost apre la pagina della regione sulla mappa con la chiave "map".
    @Test
    fun `la chiave usata dalla navigazione apre la mappa`() {
        assertEquals(RegionTab.MAP, RegionTab.fromKey("map"))
    }
}
