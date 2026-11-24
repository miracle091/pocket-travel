package com.pockettravel.feature.ai

import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.pockettravel.feature.ai.testing.FakeBergamotModelDownloads
import com.pockettravel.feature.ai.testing.FakeBergamotModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import com.pockettravel.core.ui.R as UiR

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationModelViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val manager = FakeBergamotModelManager()
    private val downloads = FakeBergamotModelDownloads()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // stateIn con WhileSubscribed: lo stato si aggiorna solo con un collettore.
    private fun newViewModel() = TranslationModelViewModel(manager, downloads).also { it.uiState.launchIn(CoroutineScope(dispatcher)) }

    @Test
    fun `senza file e senza download mostra il pulsante di download`() = runTest(dispatcher) {
        assertEquals(TranslationModelUiState(), newViewModel().uiState.value)
    }

    @Test
    fun `durante il download mostra l'avanzamento, a file installati lo stato installato`() = runTest(dispatcher) {
        val viewModel = newViewModel()

        downloads.status.value = BergamotDownloadStatus.Running(0.4f)
        assertEquals(0.4f, viewModel.uiState.value.downloadProgress)

        manager.setInstalled(true)
        downloads.status.value = BergamotDownloadStatus.Idle
        assertEquals(TranslationModelUiState(isInstalled = true), viewModel.uiState.value)
    }

    @Test
    fun `un download fallito mostra il motivo`() = runTest(dispatcher) {
        val viewModel = newViewModel()

        downloads.status.value = BergamotDownloadStatus.Failed(BergamotDownloadFailure.INTEGRITY)
        assertEquals(R.string.ai_error_integrity, viewModel.uiState.value.errorMessage)
        downloads.status.value = BergamotDownloadStatus.Failed(BergamotDownloadFailure.SPACE)
        assertEquals(R.string.translation_model_error_space, viewModel.uiState.value.errorMessage)
        downloads.status.value = BergamotDownloadStatus.Failed(BergamotDownloadFailure.OTHER)
        assertEquals(R.string.translation_model_error_download, viewModel.uiState.value.errorMessage)
        assertNull(viewModel.uiState.value.downloadProgress)
    }

    @Test
    fun `un blocco della rete mostra il messaggio con le impostazioni di Android`() = runTest(dispatcher) {
        val viewModel = newViewModel()

        downloads.status.value = BergamotDownloadStatus.Failed(BergamotDownloadFailure.DATA_SAVER)
        assertEquals(UiR.string.network_blocked_data_saver, viewModel.uiState.value.errorMessage)
        downloads.status.value = BergamotDownloadStatus.Failed(BergamotDownloadFailure.NETWORK_BLOCKED)
        assertEquals(UiR.string.network_blocked_suspected, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `scarica, annulla ed elimina, e l'eliminazione ferma prima un download in corso`() = runTest(dispatcher) {
        manager.setInstalled(true)
        val viewModel = newViewModel()

        viewModel.download()
        viewModel.cancelDownload()
        viewModel.delete()

        assertEquals(1, downloads.enqueued)
        assertEquals(2, downloads.cancelled)
        assertEquals(1, manager.deleted)
        assertFalse(viewModel.uiState.value.isInstalled)
    }

    @Test
    fun `lo stato del lavoro diventa lo stato del download`() {
        val progress = workDataOf(LlmModelDownloadWorker.KEY_BYTES_DOWNLOADED to 25L, LlmModelDownloadWorker.KEY_TOTAL_BYTES to 100L)
        val empty = workDataOf()
        val noSpace = workDataOf(LlmModelDownloadWorker.KEY_FAILURE_REASON to LlmModelDownloadWorker.FAILURE_REASON_SPACE)

        assertEquals(BergamotDownloadStatus.Running(0.25f), downloadStatus(WorkInfo.State.RUNNING, progress, empty))
        assertEquals(BergamotDownloadStatus.Running(0f), downloadStatus(WorkInfo.State.ENQUEUED, empty, empty))
        assertEquals(BergamotDownloadStatus.Idle, downloadStatus(WorkInfo.State.SUCCEEDED, empty, empty))
        assertEquals(BergamotDownloadStatus.Idle, downloadStatus(WorkInfo.State.CANCELLED, progress, empty))
        assertEquals(BergamotDownloadStatus.Failed(BergamotDownloadFailure.SPACE), downloadStatus(WorkInfo.State.FAILED, empty, noSpace))
        assertEquals(BergamotDownloadStatus.Failed(BergamotDownloadFailure.OTHER), downloadStatus(WorkInfo.State.FAILED, empty, empty))
        val dataSaver = workDataOf(LlmModelDownloadWorker.KEY_FAILURE_REASON to LlmModelDownloadWorker.FAILURE_REASON_DATA_SAVER)
        val blocked = workDataOf(LlmModelDownloadWorker.KEY_FAILURE_REASON to LlmModelDownloadWorker.FAILURE_REASON_NETWORK_BLOCKED)
        assertEquals(BergamotDownloadStatus.Failed(BergamotDownloadFailure.DATA_SAVER), downloadStatus(WorkInfo.State.FAILED, empty, dataSaver))
        assertEquals(BergamotDownloadStatus.Failed(BergamotDownloadFailure.NETWORK_BLOCKED), downloadStatus(WorkInfo.State.FAILED, empty, blocked))
    }
}
