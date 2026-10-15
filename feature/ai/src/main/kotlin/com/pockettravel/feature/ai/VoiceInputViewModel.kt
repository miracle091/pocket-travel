package com.pockettravel.feature.ai

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.currentGuidesLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VoiceInputUiState(
    // Falso sotto Android 12 o senza riconoscitore sul dispositivo: il pulsante del microfono non compare.
    val isAvailable: Boolean,
    val isListening: Boolean = false,
    @StringRes val message: Int? = null,
)

/** Dettatura della domanda dell'assistente: il testo riconosciuto va nel campo, da correggere e inviare a mano. */
@HiltViewModel
class VoiceInputViewModel @Inject constructor(private val voiceInput: VoiceInput) : ViewModel() {

    private val _uiState = MutableStateFlow(VoiceInputUiState(isAvailable = voiceInput.isAvailable))
    val uiState: StateFlow<VoiceInputUiState> = _uiState.asStateFlow()

    // Testo completo del campo durante la dettatura: conta solo l'ultimo.
    private val _text = Channel<String>(Channel.CONFLATED)
    val text: Flow<String> = _text.receiveAsFlow()

    private var listenJob: Job? = null

    /** Ascolta e aggiunge la frase dopo [currentText], il testo gia' nel campo. */
    fun start(currentText: String) {
        if (listenJob != null || !_uiState.value.isAvailable) return
        val before = currentText.trim()
        _uiState.update { it.copy(isListening = true, message = null) }
        listenJob = viewModelScope.launch {
            try {
                voiceInput.listen(if (currentGuidesLanguage() == "en") "en-US" else "it-IT").collect { event ->
                    when (event) {
                        is VoiceInputEvent.Partial -> _text.trySend(join(before, event.text))
                        is VoiceInputEvent.Final -> _text.trySend(join(before, event.text))
                        is VoiceInputEvent.Error -> _uiState.update { it.copy(message = messageFor(event.reason)) }
                    }
                }
            } finally {
                if (listenJob == coroutineContext[Job]) finish()
            }
        }
    }

    /** Smette di ascoltare: nel campo resta il testo riconosciuto fin qui. */
    fun stop() {
        listenJob?.cancel()
        finish()
    }

    fun onPermissionDenied() {
        _uiState.update { it.copy(message = R.string.ai_voice_permission) }
    }

    fun clearMessage() {
        if (_uiState.value.message != null) _uiState.update { it.copy(message = null) }
    }

    private fun finish() {
        listenJob = null
        _uiState.update { it.copy(isListening = false) }
    }
}

private fun join(before: String, dictated: String) = if (before.isEmpty()) dictated else "$before $dictated"

@StringRes
private fun messageFor(error: VoiceInputError): Int = when (error) {
    VoiceInputError.NO_SPEECH -> R.string.ai_voice_no_speech
    VoiceInputError.LANGUAGE_DOWNLOAD_OFFERED -> R.string.ai_voice_language_download
    VoiceInputError.LANGUAGE_UNAVAILABLE -> R.string.ai_voice_language_unavailable
    VoiceInputError.OTHER -> R.string.ai_voice_error
}
