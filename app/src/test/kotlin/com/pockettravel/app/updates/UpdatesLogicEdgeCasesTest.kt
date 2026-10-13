package com.pockettravel.app.updates

import com.pockettravel.app.regions.RegionStatus
import com.pockettravel.app.regions.RegionUiItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesLogicEdgeCasesTest {
    private fun item(id: String, status: RegionStatus, bytes: Long = 0, includesMap: Boolean = false) =
        RegionUiItem(id, id, bytes, status, includesMap = includesMap)

    @Test
    fun `i byte oltre quattro giga non vanno in overflow`() {
        val big = listOf("a", "b", "c").map { item(it, RegionStatus.UPDATE_AVAILABLE, bytes = 2L shl 30) }

        assertEquals(6L shl 30, updateTotal(big).bytes)
    }

    @Test
    fun `il totale somma qualunque voce gli si passi, senza filtrare per stato`() {
        val mixed = listOf(item("a", RegionStatus.INSTALLED, 5), item("b", RegionStatus.UPDATE_AVAILABLE, 7))

        assertEquals(12L, updateTotal(mixed).bytes)
    }

    @Test
    fun `una sola nazione con la mappa basta a segnalarla nel totale`() {
        val list = listOf(
            item("a", RegionStatus.UPDATE_AVAILABLE, 1),
            item("b", RegionStatus.UPDATE_AVAILABLE, 1, includesMap = true),
            item("c", RegionStatus.UPDATE_AVAILABLE, 1),
        )

        assertTrue(updateTotal(list).includesMap)
    }

    @Test
    fun `ogni stato diverso da da aggiornare resta fuori da Aggiorna tutto`() {
        val list = listOf(item("a", RegionStatus.INSTALLED), item("b", RegionStatus.NOT_INSTALLED))

        assertEquals(emptyList<RegionUiItem>(), pendingRegionUpdates(list, emptySet()))
    }

    @Test
    fun `i download di nazioni sconosciute non toccano l'elenco`() {
        val list = listOf(item("a", RegionStatus.UPDATE_AVAILABLE))

        assertEquals(listOf("a"), pendingRegionUpdates(list, setOf("x", "y")).map { it.regionId })
    }

    @Test
    fun `una nazione ripetuta nell'elenco resta ripetuta`() {
        val list = listOf(item("a", RegionStatus.UPDATE_AVAILABLE), item("a", RegionStatus.UPDATE_AVAILABLE))

        assertEquals(2, outdatedRegions(list).size)
    }

    @Test
    fun `se tutto e' in download la conferma non serve nemmeno su rete a consumo`() {
        val list = listOf(item("a", RegionStatus.UPDATE_AVAILABLE))
        val pending = pendingRegionUpdates(list, setOf("a"))

        assertFalse(updateAllNeedsConfirmation(metered = true, pending = pending))
    }

    @Test
    fun `il badge somma tutti gli aggiornamenti insieme`() {
        assertEquals(4, updatesCount(appUpdate = true, regionUpdates = 1, guidesUpdate = true, modelUpdate = true))
        assertEquals(103, updatesCount(appUpdate = true, regionUpdates = 100, guidesUpdate = true, modelUpdate = true))
    }

    @Test
    fun `il controllo e' offline solo quando mancano sia app-status sia la rete`() {
        val offline = listOf(false, true)
            .flatMap { reached -> listOf(false, true).map { online -> Triple(reached, online, updateCheckOffline(reached, online)) } }
            .filter { it.third }

        assertEquals(listOf(Triple(false, false, true)), offline)
    }
}
