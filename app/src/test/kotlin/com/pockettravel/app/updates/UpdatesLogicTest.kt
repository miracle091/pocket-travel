package com.pockettravel.app.updates

import com.pockettravel.app.R
import com.pockettravel.app.regions.PackageUiState
import com.pockettravel.app.regions.RegionStatus
import com.pockettravel.app.regions.RegionUiItem
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.sync.AppUpdateFailure
import com.pockettravel.core.sync.AppUpdateState
import com.pockettravel.core.sync.AppVersionEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `senza nazioni da aggiornare non c'e' nulla da fare`() {
        val upToDate = items.filter { it.status != RegionStatus.UPDATE_AVAILABLE }

        assertEquals(emptyList<RegionUiItem>(), outdatedRegions(upToDate))
        assertEquals(emptyList<RegionUiItem>(), outdatedRegions(emptyList()))
        assertEquals(UpdateTotal(bytes = 0, includesMap = false), updateTotal(emptyList()))
    }

    @Test
    fun `se tutte le nazioni sono gia' in download Aggiorna tutto non ha nulla in coda`() {
        val all = setOf("italia", "francia")

        assertEquals(emptyList<RegionUiItem>(), pendingRegionUpdates(items, downloading = all))
        // Il download di una nazione non aggiornabile non cambia l'elenco.
        assertEquals(listOf("italia", "francia"), pendingRegionUpdates(items, downloading = setOf("spagna")).map { it.regionId })
    }

    @Test
    fun `Aggiorna tutto mantiene l'ordine dell'elenco`() {
        assertEquals(listOf("italia", "francia"), pendingRegionUpdates(items, downloading = emptySet()).map { it.regionId })
        assertEquals(listOf("francia", "italia"), pendingRegionUpdates(items.reversed().filter { it.regionId != "grecia" }, emptySet()).map { it.regionId })
    }

    @Test
    fun `una mappa sola pesa zero byte ma il totale la segnala`() {
        val total = updateTotal(listOf(item("lettonia", RegionStatus.UPDATE_AVAILABLE, bytes = 0, includesMap = true)))

        assertEquals(UpdateTotal(bytes = 0, includesMap = true), total)
    }

    @Test
    fun `una mappa da aggiornare pesa circa quanto quella installata`() {
        val map = PackageUiState(PackageKind.MAP, RegionStatus.UPDATE_AVAILABLE, downloadBytes = 0, installedBytes = 30L shl 20)
        val withMap = RegionUiItem("lettonia", "Lettonia", 5L shl 20, RegionStatus.UPDATE_AVAILABLE, packages = listOf(map), includesMap = true)

        assertEquals(30L shl 20, withMap.mapEstimateBytes())
        assertEquals(UpdateTotal(bytes = 35L shl 20, includesMap = false, estimated = true), updateTotal(listOf(withMap)))
        // Senza il peso della mappa installata resta "+ mappa".
        val unknown = withMap.copy(packages = listOf(map.copy(installedBytes = null)))
        assertEquals(UpdateTotal(bytes = 5L shl 20, includesMap = true), updateTotal(listOf(unknown)))
    }

    @Test
    fun `il badge conta ogni nazione una volta e ogni altro aggiornamento una volta`() {
        assertEquals(1, updatesCount(appUpdate = true, regionUpdates = 0, guidesUpdate = false, modelUpdate = false))
        assertEquals(1, updatesCount(appUpdate = false, regionUpdates = 0, guidesUpdate = true, modelUpdate = false))
        assertEquals(1, updatesCount(appUpdate = false, regionUpdates = 0, guidesUpdate = false, modelUpdate = true))
        assertEquals(outdatedRegions(items).size, updatesCount(appUpdate = false, regionUpdates = outdatedRegions(items).size, guidesUpdate = false, modelUpdate = false))
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

    @Test
    fun `un apk pronto di un'altra versione non riguarda l'aggiornamento mostrato`() {
        val app = AppVersionEntry("1.2.0", 20)
        val old = AppUpdateState.Ready(19, java.io.File("19.apk"))
        val current = AppUpdateState.Ready(20, java.io.File("20.apk"))
        assertEquals(AppUpdateState.Idle, relevantDownload(old, app))
        assertEquals(current, relevantDownload(current, app))
        assertEquals(AppUpdateState.Idle, relevantDownload(AppUpdateState.Idle, app))
        // Un errore di una versione precedente non blocca quella nuova.
        assertEquals(AppUpdateState.Idle, relevantDownload(AppUpdateState.Failed(AppUpdateFailure.SIGNATURE, 19), app))
        val failed = AppUpdateState.Failed(AppUpdateFailure.SIGNATURE, 20)
        assertEquals(failed, relevantDownload(failed, app))
    }

    @Test
    fun `l'avanzamento del download dell'app e' noto solo con il totale`() {
        assertNull(AppUpdateState.Downloading(10L, 0L).fraction())
        assertEquals(0.25f, AppUpdateState.Downloading(250L, 1_000L).fraction())
        assertEquals(1f, AppUpdateState.Downloading(2_000L, 1_000L).fraction())
    }

    @Test
    fun `ogni fallimento del download ha il suo testo`() {
        assertEquals(R.string.updates_app_error_signature, AppUpdateFailure.SIGNATURE.messageRes())
        assertEquals(R.string.updates_app_error_download, AppUpdateFailure.NETWORK.messageRes())
        assertEquals(R.string.updates_app_error_file, AppUpdateFailure.INVALID_FILE.messageRes())
        assertEquals(R.string.updates_app_error_file, AppUpdateFailure.NOT_NEWER.messageRes())
    }

    @Test
    fun `si riprova solo dopo un errore che un nuovo download puo' evitare`() {
        assertTrue(AppUpdateFailure.NETWORK.canRetry())
        assertTrue(AppUpdateFailure.INVALID_FILE.canRetry())
        assertFalse(AppUpdateFailure.UNREADABLE.canRetry())
        assertFalse(AppUpdateFailure.PACKAGE.canRetry())
        assertFalse(AppUpdateFailure.NOT_NEWER.canRetry())
        assertFalse(AppUpdateFailure.SIGNATURE.canRetry())
    }
}
