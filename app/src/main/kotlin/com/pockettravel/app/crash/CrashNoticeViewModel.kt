package com.pockettravel.app.crash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.app.runCatchingCancellable
import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.currentGuidesLanguage
import com.pockettravel.feature.ai.AiSettingsStore
import com.pockettravel.feature.ai.LlmModelDownloadScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Modello più grande della RAM del dispositivo che si è chiuso, e quello adatto da scaricare. */
data class ModelProposal(val loadedName: String, val proposedId: String, val proposedName: String)

/** Rapporto in attesa da mostrare: testo, indirizzo del modulo su GitHub e proposta del modello. Null: nessun avviso. */
data class CrashNoticeState(
    val text: String,
    val issueUrl: String,
    val proposal: ModelProposal?,
)

@HiltViewModel
class CrashNoticeViewModel @Inject constructor(
    private val reporter: CrashReporter,
    private val aiSettingsStore: AiSettingsStore,
    private val downloadScheduler: LlmModelDownloadScheduler,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _state = MutableStateFlow<CrashNoticeState?>(null)
    val state: StateFlow<CrashNoticeState?> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val report = withContext(ioDispatcher) {
                runCatchingCancellable { reporter.collectOnce() }
                reporter.store.readPending()
            }
            if (report != null) _state.update { noticeFor(report) }
        }
    }

    private fun noticeFor(report: CrashReport): CrashNoticeState {
        val proposal = proposedModel(report, currentGuidesLanguage())?.let { (loaded, proposed) ->
            ModelProposal(loaded.displayName, proposed.id, proposed.displayName)
        }
        return CrashNoticeState(report.toText(), report.issueUrl(), proposal)
    }

    /** "Chiudi": il rapporto si cancella. */
    fun dismiss() {
        _state.update { null }
        viewModelScope.launch { withContext(ioDispatcher) { reporter.store.clearPending() } }
    }

    /** Stesso flusso del download dell'assistente: sceglie il modello e avvia il download. */
    fun downloadProposed(modelId: String) {
        aiSettingsStore.setSelectedModelId(modelId)
        downloadScheduler.enqueueDownload()
        dismiss()
    }
}
