package com.pockettravel.feature.map

import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.core.ui.PoiColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PoiPinsTest {

    @Test
    fun `solo ALTRO ha il segnalino generico senza glifo`() {
        val withoutGlyph = PoiCategory.entries.filter { it.glyph() == null }

        assertEquals(listOf(PoiCategory.ALTRO), withoutGlyph)
    }

    @Test
    fun `ALTRO ha il colore di ripiego, nessun'altra categoria lo usa`() {
        assertEquals(PoiColors.Other, PoiCategory.ALTRO.pinColor())
        assertEquals(listOf(PoiCategory.ALTRO), PoiCategory.entries.filter { it.pinColor() == PoiColors.Other })
    }

    @Test
    fun `ogni categoria ha un colore diverso da quello del glifo bianco`() {
        PoiCategory.entries.forEach { assertNotEquals(it.name, PoiColors.Glyph, it.pinColor()) }
    }

    @Test
    fun `alloggio, cibo e negozi hanno ognuno il suo colore`() {
        assertEquals(PoiColors.Lodging, PoiCategory.ALLOGGIO.pinColor())
        assertEquals(PoiColors.FoodDrink, PoiCategory.CIBO_BEVANDE.pinColor())
        assertEquals(PoiColors.Shopping, PoiCategory.NEGOZI.pinColor())
        assertEquals(PoiColors.Shopping, PoiCategory.DISTRIBUTORI.pinColor())
    }

    @Test
    fun `tutto quello da vedere ha lo stesso colore, lo distingue il glifo`() {
        val toSee = listOf(
            PoiCategory.LUOGHI_DI_CULTO, PoiCategory.MUSEI_ARTE, PoiCategory.LUOGHI_STORICI, PoiCategory.NATURA, PoiCategory.PANORAMI,
            PoiCategory.PARCHI_DIVERTIMENTO, PoiCategory.ZOO, PoiCategory.PARCHI_ACQUATICI, PoiCategory.ATTRAZIONI,
        )

        toSee.forEach { assertEquals(it.name, PoiColors.Attractions, it.pinColor()) }
        assertEquals(toSee.size, toSee.map { it.glyph() }.toSet().size)
    }

    @Test
    fun `svago e sport hanno il colore dello svago`() {
        assertEquals(PoiColors.Entertainment, PoiCategory.SVAGO.pinColor())
        assertEquals(PoiColors.Entertainment, PoiCategory.SPORT.pinColor())
    }

    @Test
    fun `mezzi di trasporto e servizi per i veicoli hanno il colore dei trasporti`() {
        listOf(
            PoiCategory.TRENO, PoiCategory.METRO, PoiCategory.AUTOBUS, PoiCategory.TAXI, PoiCategory.TRAGHETTO, PoiCategory.AEROPORTO,
            PoiCategory.PARCHEGGIO, PoiCategory.CARBURANTE, PoiCategory.RICARICA, PoiCategory.NOLEGGIO,
        ).forEach { assertEquals(it.name, PoiColors.Transport, it.pinColor()) }
    }

    @Test
    fun `servizi pubblici, salute e denaro hanno il colore dei servizi`() {
        listOf(
            PoiCategory.POLIZIA, PoiCategory.FARMACIA, PoiCategory.OSPEDALE, PoiCategory.BANCA, PoiCategory.BANCOMAT,
            PoiCategory.UFFICIO_POSTALE, PoiCategory.INFORMAZIONI, PoiCategory.AMBASCIATA_CONSOLATO,
        ).forEach { assertEquals(it.name, PoiColors.PublicServices, it.pinColor()) }
    }

    @Test
    fun `i parcheggi pubblici e privati hanno lo stesso glifo`() {
        assertNotNull(PoiCategory.PARCHEGGIO.glyph())
        assertEquals(PoiCategory.PARCHEGGIO.glyph(), PoiCategory.PARCHEGGIO_PRIVATO.glyph())
        assertNotEquals(PoiCategory.PARCHEGGIO.glyph(), PoiCategory.PARCHEGGIO_DISABILI.glyph())
    }

    @Test
    fun `il distintivo di accessibilita' segue il tag wheelchair di OSM`() {
        assertEquals(AccessibilityBadge.YES, accessibilityBadgeOf("yes"))
        assertEquals(AccessibilityBadge.YES, accessibilityBadgeOf("designated"))
        assertEquals(AccessibilityBadge.LIMITED, accessibilityBadgeOf("limited"))
    }

    @Test
    fun `nessun distintivo se l'accessibilita' manca, e' negata o ha un valore sconosciuto`() {
        assertNull(accessibilityBadgeOf(null))
        assertNull(accessibilityBadgeOf("no"))
        assertNull(accessibilityBadgeOf(""))
        // Il tag di OSM e' in minuscolo: un valore diverso non si indovina.
        assertNull(accessibilityBadgeOf("Yes"))
    }

    @Test
    fun `le immagini dei distintivi hanno id diversi, pieno e vuoto`() {
        assertNotEquals(AccessibilityBadge.YES.imageId, AccessibilityBadge.LIMITED.imageId)
        assertEquals("badge-accessible", AccessibilityBadge.YES.imageId)
        assertEquals("badge-accessible-limited", AccessibilityBadge.LIMITED.imageId)
    }
}
