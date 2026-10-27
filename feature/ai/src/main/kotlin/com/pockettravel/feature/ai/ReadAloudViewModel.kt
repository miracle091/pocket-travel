package com.pockettravel.feature.ai

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.currentGuidesLanguage
import com.pockettravel.core.speech.ReadAloud
import com.pockettravel.core.speech.ReadAloudResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReadAloudUiState(
    // La risposta che si sta leggendo (il suo testo), null se la voce e' ferma.
    val speakingText: String? = null,
    // Il testo della risposta il cui ascolto non e' riuscito, con il motivo.
    val failedText: String? = null,
    @StringRes val message: Int? = null,
)

/** Lettura ad alta voce delle risposte dell'assistente: una alla volta, nella lingua delle guide come le risposte. */
@HiltViewModel
class ReadAloudViewModel @Inject constructor(private val readAloud: ReadAloud) : ViewModel() {

    private val _uiState = MutableStateFlow(ReadAloudUiState())
    val uiState: StateFlow<ReadAloudUiState> = _uiState.asStateFlow()

    private var speakJob: Job? = null

    /** Legge [text], fermando la risposta letta prima; se [text] e' gia' in lettura la ferma. */
    fun toggle(text: String) {
        val wasSpeaking = _uiState.value.speakingText == text
        stop()
        if (wasSpeaking) return
        _uiState.update { ReadAloudUiState(speakingText = text) }
        speakJob = viewModelScope.launch {
            val result = readAloud.speak(text, currentGuidesLanguage())
            speakJob = null
            _uiState.update {
                when (result) {
                    ReadAloudResult.FINISHED -> ReadAloudUiState()
                    ReadAloudResult.LANGUAGE_UNAVAILABLE -> ReadAloudUiState(failedText = text, message = R.string.ai_read_aloud_language_unavailable)
                    ReadAloudResult.FAILED -> ReadAloudUiState(failedText = text, message = R.string.ai_read_aloud_error)
                }
            }
        }
    }

    fun stop() {
        speakJob?.cancel()
        speakJob = null
        _uiState.update { ReadAloudUiState() }
    }
}
