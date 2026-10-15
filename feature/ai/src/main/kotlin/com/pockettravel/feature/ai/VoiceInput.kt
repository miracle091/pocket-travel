package com.pockettravel.feature.ai

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

/** Cosa succede mentre si detta: testo provvisorio, testo finale o errore; dopo Final ed Error il flusso finisce. */
sealed interface VoiceInputEvent {
    data class Partial(val text: String) : VoiceInputEvent
    data class Final(val text: String) : VoiceInputEvent
    data class Error(val reason: VoiceInputError) : VoiceInputEvent
}

enum class VoiceInputError {
    // Nessuna parola riconosciuta (silenzio o voce troppo bassa).
    NO_SPEECH,

    // Il pacchetto della lingua manca: il telefono ne ha chiesto lo scaricamento.
    LANGUAGE_DOWNLOADING,

    // Il pacchetto della lingua manca e non si puo' chiedere (Android 12) o la lingua non c'e'.
    LANGUAGE_UNAVAILABLE,
    OTHER,
}

/** Riconoscimento vocale sul dispositivo: l'audio resta al servizio del telefono, l'app riceve solo il testo. */
interface VoiceInput {
    /** Vero se il telefono ha un riconoscitore sul dispositivo (da Android 12). */
    val isAvailable: Boolean

    /** Ascolta il microfono fino alla fine della frase; annullare la raccolta smette di ascoltare. Sul thread principale. */
    fun listen(language: String): Flow<VoiceInputEvent>
}

class OnDeviceVoiceInput @Inject constructor(@ApplicationContext private val context: Context) : VoiceInput {

    override val isAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun listen(language: String): Flow<VoiceInputEvent> = callbackFlow {
        // Non succede: sotto Android 12 isAvailable e' falso e il microfono non compare.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            trySend(VoiceInputEvent.Error(VoiceInputError.OTHER))
            close()
            awaitClose()
            return@callbackFlow
        }
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        var lastPartial = ""
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle) {
                val text = partialResults.firstResult()
                if (text.isNotBlank()) {
                    lastPartial = text
                    trySend(VoiceInputEvent.Partial(text))
                }
            }

            // Alcuni servizi lasciano vuoto il risultato finale e mettono il testo solo nei parziali.
            override fun onResults(results: Bundle) {
                val text = results.firstResult().ifBlank { lastPartial }
                trySend(if (text.isBlank()) VoiceInputEvent.Error(VoiceInputError.NO_SPEECH) else VoiceInputEvent.Final(text))
                close()
            }

            override fun onError(error: Int) {
                trySend(VoiceInputEvent.Error(errorFor(error, recognizer, intent)))
                close()
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(intent)
        awaitClose { recognizer.destroy() }
    }

    private fun errorFor(error: Int, recognizer: SpeechRecognizer, intent: Intent): VoiceInputError = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceInputError.NO_SPEECH
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            // Da Android 13 il telefono mostra la sua finestra per scaricare il pacchetto della lingua.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                recognizer.triggerModelDownload(intent)
                VoiceInputError.LANGUAGE_DOWNLOADING
            } else {
                VoiceInputError.LANGUAGE_UNAVAILABLE
            }
        else -> VoiceInputError.OTHER
    }
}

private fun Bundle.firstResult(): String = getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
