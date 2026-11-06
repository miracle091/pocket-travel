package com.pockettravel.app.updates

import androidx.work.Data
import androidx.work.WorkInfo
import com.pockettravel.app.InstalledVersionCode
import com.pockettravel.app.MainDispatcherRule
import com.pockettravel.app.R
import com.pockettravel.app.manifestFile
import com.pockettravel.app.regionManifest
import com.pockettravel.core.data.InstalledGuides
import com.pockettravel.core.data.testing.FakeRegionRepository
import com.pockettravel.core.sync.AiModelManifestEntry
import com.pockettravel.core.sync.AppStatus
import com.pockettravel.core.sync.AppUpdateFailure
import com.pockettravel.core.sync.AppUpdateState
import com.pockettravel.core.sync.AppVersionEntry
import com.pockettravel.core.sync.GuidesManifestEntry
import com.pockettravel.core.sync.testing.FakeAppStatusClient
import com.pockettravel.core.sync.testing.FakeAppUpdateDownloads
import com.pockettravel.core.sync.testing.FakeManifestClient
import com.pockettravel.core.sync.testing.FakeRegionSyncScheduler
import com.pockettravel.feature.ai.LlmModelDownloadWorker
import com.pockettravel.feature.ai.testing.FakeAiModelUpdates
import com.pockettravel.feature.map.ConnectivityChecker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private var installedVersionCode = 10L
    private val status = FakeAppStatusClient()
    private val manifests = FakeManifestClient(regionManifest())
    private val repository = FakeRegionRepository()
    private val scheduler = FakeRegionSyncScheduler()
    private val models = FakeAiModelUpdates()
    private val connectivity = FakeConnectivityChecker()
    private val downloads = FakeAppUpdateDownloads()
    private val installer = FakeAppInstaller()
    private lateinit var previousLocale: Locale

    private val newApp = AppVersionEntry("1.2.0", 20)
    private val newAppWithApk = AppVersionEntry("1.2.0", 20, apkSha256 = "a".repeat(64), apkSizeBytes = 19_000_000L)
    private val newModel = AiModelManifestEntry("qwen3.5-0.8b", "v2.gguf", "d".repeat(64), 500L)

    @Before
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ITALIAN)
        repository.guides.value = InstalledGuides("g1", 100L)
    }

    @After
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    private fun viewModel() = UpdatesViewModel(
        InstalledVersionCode { installedVersionCode }, status, manifests, repository, scheduler, models, connectivity, downloads, installer,
    )

    private fun work(state: WorkInfo.State, progress: Data = Data.EMPTY) = WorkInfo(UUID.randomUUID(), state, emptySet(), progress = progress)

    private fun modelProgress(downloaded: Long, total: Long) = Data.Builder()
        .putLong(LlmModelDownloadWorker.KEY_BYTES_DOWNLOADED, downloaded)
        .putLong(LlmModelDownloadWorker.KEY_TOTAL_BYTES, total)
        .build()

    @Test
    fun `finche' il controllo non finisce la schermata e' in controllo e senza aggiornamenti`() = runTest {
        val gate = CompletableDeferred<Unit>()
        status.gate = gate
        status.status = AppStatus(appVersion = newApp)

        val viewModel = viewModel()
        runCurrent()

        assertEquals(UpdatesUiState(), viewModel.uiState.value)
        assertTrue(viewModel.uiState.value.isChecking)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isChecking)
        assertEquals(newApp, viewModel.uiState.value.appUpdate)
    }

    @Test
    fun `una versione dell'app piu' recente di quella installata e' un aggiornamento`() = runTest {
        status.status = AppStatus(appVersion = newApp)

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(newApp, viewModel.uiState.value.appUpdate)
    }

    @Test
    fun `la stessa versione, una piu' vecchia o nessuna versione pubblicata non sono aggiornamenti`() = runTest {
        status.status = AppStatus(appVersion = AppVersionEntry("1.0.0", 10))
        val same = viewModel()
        advanceUntilIdle()
        assertNull(same.uiState.value.appUpdate)

        installedVersionCode = 30L
        val older = viewModel()
        advanceUntilIdle()
        assertNull(older.uiState.value.appUpdate)

        status.status = AppStatus(appVersion = null)
        val none = viewModel()
        advanceUntilIdle()
        assertNull(none.uiState.value.appUpdate)
    }

    @Test
    fun `il modello e' da aggiornare quando lo dice la schermata dei modelli, sui modelli pubblicati`() = runTest {
        status.status = AppStatus(aiModels = listOf(newModel))
        models.update = newModel

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(newModel, viewModel.uiState.value.modelUpdate)
        assertEquals(listOf(listOf(newModel)), models.published)
    }

    @Test
    fun `se il controllo del modello fallisce non c'e' aggiornamento e il resto funziona`() = runTest {
        status.status = AppStatus(appVersion = newApp, aiModels = listOf(newModel))
        models.availableFailure = IllegalStateException("modello illeggibile")

        val viewModel = viewModel()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.modelUpdate)
        assertEquals(newApp, viewModel.uiState.value.appUpdate)
        assertFalse(viewModel.uiState.value.isChecking)
    }

    @Test
    fun `le guide installate con una versione diversa dal catalogo sono da aggiornare, col peso compresso`() = runTest {
        manifests.manifest = regionManifest(guidesVersion = "g2", guidesBytes = 2_500L)

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(GuidesUpdate(2_500L), viewModel.uiState.value.guidesUpdate)
    }

    @Test
    fun `le guide alla versione del catalogo non sono un aggiornamento`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.guidesUpdate)
    }

    @Test
    fun `senza guide installate le guide del catalogo sono da scaricare`() = runTest {
        repository.guides.value = null

        val viewModel = viewModel()
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.guidesUpdate)
    }

    @Test
    fun `con l'interfaccia in inglese si confronta la versione delle guide inglesi`() = runTest {
        Locale.setDefault(Locale.ENGLISH)
        manifests.manifest = regionManifest().copy(guidesEn = GuidesManifestEntry("g1", manifestFile("guides-en.db", 9_000L)))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals("le guide installate sono quelle italiane", GuidesUpdate(9_000L), viewModel.uiState.value.guidesUpdate)
    }

    @Test
    fun `senza rete il controllo lo dice, e con il pulsante manda anche il messaggio`() = runTest {
        status.failure = IOException("offline")
        connectivity.online = false

        val viewModel = viewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.offline)
        assertNull("all'apertura niente messaggio", viewModel.uiState.value.message)

        viewModel.refresh(userRequested = true)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.offline)
        assertEquals(R.string.updates_offline, viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.isChecking)

        viewModel.onMessageShown()
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `un errore del server con la rete presente non e' offline e tiene i valori di prima`() = runTest {
        status.status = AppStatus(appVersion = newApp)
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(newApp, viewModel.uiState.value.appUpdate)

        status.failure = IOException("HTTP 503")
        viewModel.refresh(userRequested = true)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.offline)
        assertNull(viewModel.uiState.value.message)
        assertEquals(newApp, viewModel.uiState.value.appUpdate)
    }

    @Test
    fun `se app-status arriva il controllo non e' offline anche senza rete rilevata`() = runTest {
        connectivity.online = false

        val viewModel = viewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.offline)
    }

    @Test
    fun `con il catalogo irraggiungibile le guide restano come prima e il controllo finisce`() = runTest {
        manifests.manifest = regionManifest(guidesVersion = "g2")
        val viewModel = viewModel()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.guidesUpdate)

        manifests.failure = IOException("offline")
        viewModel.refresh()
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.guidesUpdate)
        assertFalse(viewModel.uiState.value.isChecking)
    }

    @Test
    fun `se app-status non e' leggibile le guide si controllano lo stesso`() = runTest {
        status.failure = IOException("HTTP 503")
        manifests.manifest = regionManifest(guidesVersion = "g2")

        val viewModel = viewModel()
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.guidesUpdate)
        assertNull(viewModel.uiState.value.appUpdate)
    }

    @Test
    fun `ricontrollare dopo un aggiornamento fatto toglie quello che non c'e' piu'`() = runTest {
        status.status = AppStatus(appVersion = newApp)
        val viewModel = viewModel()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.appUpdate)

        installedVersionCode = 20L
        viewModel.refresh()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.appUpdate)
    }

    @Test
    fun `aggiornare le guide chiede la sincronizzazione anche su rete mobile`() = runTest {
        viewModel().updateGuides()
        advanceUntilIdle()

        assertEquals(listOf(FakeRegionSyncScheduler.GuidesSyncRequest(onlyOnWifi = false, automatic = false)), scheduler.guidesSyncs)
    }

    @Test
    fun `la sincronizzazione delle guide in coda o in corso si vede, finita no`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.guidesSyncing)

        scheduler.guidesSync.value = work(WorkInfo.State.ENQUEUED)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.guidesSyncing)

        scheduler.guidesSync.value = work(WorkInfo.State.RUNNING)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.guidesSyncing)

        scheduler.guidesSync.value = work(WorkInfo.State.FAILED)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.guidesSyncing)
    }

    @Test
    fun `finite le guide installate l'aggiornamento sparisce`() = runTest {
        manifests.manifest = regionManifest(guidesVersion = "g2")
        val viewModel = viewModel()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.guidesUpdate)

        repository.guides.value = InstalledGuides("g2", 120L)
        scheduler.guidesSync.value = work(WorkInfo.State.SUCCEEDED)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.guidesUpdate)
        assertFalse(viewModel.uiState.value.guidesSyncing)
    }

    @Test
    fun `se a lavoro finito le guide non sono alla versione del catalogo l'aggiornamento resta`() = runTest {
        manifests.manifest = regionManifest(guidesVersion = "g2")
        val viewModel = viewModel()
        advanceUntilIdle()

        scheduler.guidesSync.value = work(WorkInfo.State.SUCCEEDED)
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.guidesUpdate)
    }

    @Test
    fun `aggiornare l'app avvia il download senza rete a consumo`() = runTest {
        status.status = AppStatus(appVersion = newAppWithApk)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.updateApp()
        advanceUntilIdle()

        assertEquals(listOf(newAppWithApk to false), downloads.started)
    }

    @Test
    fun `con una rete a consumo aggiornare l'app chiede conferma e parte solo con il si'`() = runTest {
        status.status = AppStatus(appVersion = newAppWithApk)
        downloads.metered = true
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.updateApp()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.meteredConfirmation)
        assertTrue(downloads.started.isEmpty())

        viewModel.answerMeteredUpdate(download = false)
        assertFalse(viewModel.uiState.value.meteredConfirmation)
        assertTrue(downloads.started.isEmpty())

        viewModel.updateApp()
        viewModel.answerMeteredUpdate(download = true)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.meteredConfirmation)
        assertEquals(listOf(newAppWithApk to true), downloads.started)
    }

    @Test
    fun `l'ora dell'ultimo controllo riuscito arriva nello stato e resta se il controllo dopo fallisce`() = runTest {
        status.now = 5_000L
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(5_000L, viewModel.uiState.value.lastCheckedAtMillis)

        status.now = 9_000L
        status.failure = IOException("rete")
        viewModel.refresh(userRequested = true)
        advanceUntilIdle()
        assertEquals(5_000L, viewModel.uiState.value.lastCheckedAtMillis)
    }

    @Test
    fun `senza un controllo riuscito non c'e' un'ora dell'ultimo controllo`() = runTest {
        status.failure = IOException("rete")
        val viewModel = viewModel()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.lastCheckedAtMillis)
    }

    @Test
    fun `senza aggiornamento dell'app o senza impronta il download non parte`() = runTest {
        val none = viewModel()
        advanceUntilIdle()
        none.updateApp()
        status.status = AppStatus(appVersion = newApp)
        val noApk = viewModel()
        advanceUntilIdle()
        noApk.updateApp()
        advanceUntilIdle()

        assertTrue(downloads.started.isEmpty())
    }

    @Test
    fun `lo stato del download dell'app arriva nello stato e si puo' annullare`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        downloads.state.value = AppUpdateState.Downloading(1_000L, 4_000L)
        advanceUntilIdle()
        assertEquals(AppUpdateState.Downloading(1_000L, 4_000L), viewModel.uiState.value.appDownload)

        viewModel.cancelAppDownload()
        assertEquals(1, downloads.cancelled)

        downloads.state.value = AppUpdateState.Failed(AppUpdateFailure.SIGNATURE)
        advanceUntilIdle()
        assertEquals(AppUpdateState.Failed(AppUpdateFailure.SIGNATURE), viewModel.uiState.value.appDownload)
    }

    @Test
    fun `installare senza il permesso chiede il passo verso le impostazioni`() = runTest {
        val viewModel = viewModel()
        downloads.state.value = AppUpdateState.Ready(20, File("20.apk"))
        installer.allowed = false
        advanceUntilIdle()

        viewModel.installApp()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.installPermissionNeeded)
        assertTrue(installer.installed.isEmpty())

        viewModel.openInstallSettings()
        assertEquals(1, installer.settingsOpened)
    }

    @Test
    fun `con il permesso concesso installa l'apk pronto e toglie la richiesta`() = runTest {
        val viewModel = viewModel()
        val apk = File("20.apk")
        downloads.state.value = AppUpdateState.Ready(20, apk)
        installer.allowed = false
        advanceUntilIdle()
        viewModel.installApp()
        installer.allowed = true

        viewModel.installApp()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.installPermissionNeeded)
        assertEquals(listOf(apk), installer.installed)
        assertEquals(AppInstallStatus.Installing, viewModel.uiState.value.appInstall)
    }

    @Test
    fun `installare senza un apk pronto o con un'installazione in corso non fa nulla`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.installApp()
        assertTrue(installer.installed.isEmpty())

        downloads.state.value = AppUpdateState.Ready(20, File("20.apk"))
        advanceUntilIdle()
        viewModel.installApp()
        advanceUntilIdle()
        viewModel.installApp()
        advanceUntilIdle()

        assertEquals(1, installer.installed.size)
    }

    @Test
    fun `l'installazione fallita o annullata arriva nello stato e si puo' riprovare`() = runTest {
        val viewModel = viewModel()
        downloads.state.value = AppUpdateState.Ready(20, File("20.apk"))
        advanceUntilIdle()
        viewModel.installApp()
        advanceUntilIdle()

        installer.status.value = AppInstallStatus.Failed
        advanceUntilIdle()
        assertEquals(AppInstallStatus.Failed, viewModel.uiState.value.appInstall)

        viewModel.installApp()
        advanceUntilIdle()
        assertEquals(2, installer.installed.size)

        installer.status.value = AppInstallStatus.Cancelled
        advanceUntilIdle()
        assertEquals(AppInstallStatus.Cancelled, viewModel.uiState.value.appInstall)

        viewModel.installApp()
        advanceUntilIdle()
        assertEquals(3, installer.installed.size)
    }

    @Test
    fun `scaricare il modello senza aggiornamento non fa nulla`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.downloadModel()

        assertTrue(models.downloads.isEmpty())
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `scaricare il modello accoda la versione pubblicata`() = runTest {
        status.status = AppStatus(aiModels = listOf(newModel))
        models.update = newModel
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.downloadModel()

        assertEquals(listOf(newModel), models.downloads)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `senza spazio per il modello arriva un messaggio una tantum`() = runTest {
        status.status = AppStatus(aiModels = listOf(newModel))
        models.update = newModel
        models.enoughSpace = false
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.downloadModel()

        assertEquals(R.string.updates_model_not_enough_space, viewModel.uiState.value.message)
        viewModel.onMessageShown()
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `il download del modello mostra l'avanzamento sul totale`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.modelDownloading)
        assertNull(viewModel.uiState.value.modelProgress)

        models.work.value = work(WorkInfo.State.RUNNING, modelProgress(downloaded = 250L, total = 1_000L))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.modelDownloading)
        assertEquals(0.25f, viewModel.uiState.value.modelProgress)
    }

    @Test
    fun `finche' il totale non e' noto non c'e' un avanzamento`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()

        models.work.value = work(WorkInfo.State.ENQUEUED)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.modelDownloading)
        assertNull(viewModel.uiState.value.modelProgress)
    }

    @Test
    fun `finito il download il modello si ricontrolla e l'aggiornamento sparisce`() = runTest {
        status.status = AppStatus(aiModels = listOf(newModel))
        models.update = newModel
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(newModel, viewModel.uiState.value.modelUpdate)

        models.update = null
        models.work.value = work(WorkInfo.State.SUCCEEDED)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.modelUpdate)
        assertFalse(viewModel.uiState.value.modelDownloading)
        assertEquals("ricontrollato sui modelli pubblicati", listOf(newModel), models.published.last())
    }

    @Test
    fun `senza aggiornamento del modello a lavoro finito non si ricontrolla nulla`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        val checks = models.published.size

        models.work.value = work(WorkInfo.State.SUCCEEDED)
        advanceUntilIdle()

        assertEquals(checks, models.published.size)
        assertNull(viewModel.uiState.value.modelUpdate)
    }
}

private class FakeConnectivityChecker(var online: Boolean = true) : ConnectivityChecker {
    override fun isOnline() = online
}

private class FakeAppInstaller : AppInstaller {
    override val status = MutableStateFlow(AppInstallStatus.Idle)
    var allowed = true
    var settingsOpened = 0
    val installed = mutableListOf<File>()

    override fun canInstall() = allowed

    override fun openInstallSettings() {
        settingsOpened++
    }

    override suspend fun install(file: File, versionCode: Int) {
        installed += file
        status.value = AppInstallStatus.Installing
    }
}
