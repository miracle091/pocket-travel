package com.pockettravel.app.updates

import com.pockettravel.app.regions.RegionStatus
import com.pockettravel.app.regions.RegionUiItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesLogicTest {
    private fun item(id: String, status: RegionStatus, bytes: Long = 0, includesMap: Boolean = false) =
        RegionUiItem(id, id, bytes, status, includesMap = includesMap)

    private val items = listOf(
        item("italia", RegionStatus.UPDATE_AVAILABLE, bytes = 40L shl 20),
        item("francia", RegionStatus.UPDATE_AVAILABLE, bytes = 10L shl 20, includesMap = true),
        item("spagna", RegionStatus.INSTALLED, bytes = 500L shl 20),
        item("grecia", RegionStatus.NOT_INSTALLED, bytes = 300L shl 20),
    )

    @Test
    fun `le nazioni da aggiornare sono solo quelle installate con pacchetti cambiati`() {
        assertEquals(listOf("italia", "francia"), outdatedRegions(items).map { it.regionId })
    }

    @Test
    fun `Aggiorna tutto salta le nazioni già in download`() {
        assertEquals(listOf("francia"), pendingRegionUpdates(items, downloading = setOf("italia")).map { it.regionId })
    }

    @Test
    fun `il badge somma app, nazioni, guide e modello`() {
        assertEquals(0, updatesCount(appUpdate = false, regionUpdates = 0, guidesUpdate = false, modelUpdate = false))
        assertEquals(5, updatesCount(appUpdate = true, regionUpdates = 2, guidesUpdate = true, modelUpdate = true))
        assertEquals(3, updatesCount(appUpdate = false, regionUpdates = 3, guidesUpdate = false, modelUpdate = false))
    }

    @Test
    fun `il totale somma i byte e segnala la mappa`() {
        val total = updateTotal(outdatedRegions(items))
        assertEquals(50L shl 20, total.bytes)
        assertTrue(total.includesMap)
        assertFalse(updateTotal(listOf(items[0])).includesMap)
    }

    @Test
    fun `la conferma serve solo su rete a consumo e con qualcosa da scaricare`() {
        val pending = outdatedRegions(items)
        assertTrue(updateAllNeedsConfirmation(metered = true, pending = pending))
        assertFalse(updateAllNeedsConfirmation(metered = false, pending = pending))
        assertFalse(updateAllNeedsConfirmation(metered = true, pending = emptyList()))
    }

    @Test
    fun `senza rete il controllo e' offline anche con il catalogo ancora in memoria`() {
        // Il catalogo di recentManifest(), tenuto per 10 minuti, arriva anche senza rete: non deve far dire "Tutto aggiornato".
        assertTrue(updateCheckOffline(appStatusReached = false, deviceOnline = false))
    }

    @Test
    fun `con app-status arrivato o con la rete presente il controllo non e' offline`() {
        assertFalse(updateCheckOffline(appStatusReached = true, deviceOnline = true))
        assertFalse(updateCheckOffline(appStatusReached = true, deviceOnline = false))
        // Rete presente ma app-status non arrivato: errore del server, restano i valori di prima.
        assertFalse(updateCheckOffline(appStatusReached = false, deviceOnline = true))
    }
}
