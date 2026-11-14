package com.pockettravel.core.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * La voce della navigazione: un motore di sintesi acceso per tutta la guida (accenderlo a ogni frase costerebbe
 * tempo), solo con voci installate, sul canale audio della navigazione. Mentre parla abbassa la musica.
 */
interface NavigationVoice {
    /**
     * Accende il motore con la voce installata di [language] (tag BCP 47, "it" o "en"): [ReadAloudResult.FINISHED] se e'
     * pronta, altrimenti il motivo. Sul thread principale.
     */
    suspend fun start(language: String): ReadAloudResult

    /** Dice [text], interrompendo la frase precedente; senza un [start] riuscito non fa nulla. */
    fun say(text: String)

    /** Spegne il motore, dopo la frase in corso (l'arrivo si sente anche se la navigazione si chiude subito). */
    fun stop()
}

@Singleton
class SystemNavigationVoice @Inject constructor(@ApplicationContext private val context: Context) : NavigationVoice {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(NAVIGATION_AUDIO).build()
    private val utterances = AtomicInteger()

    @Volatile private var engine: TextToSpeech? = null

    override suspend fun start(language: String): ReadAloudResult {
        stop()
        return suspendCancellableCoroutine { continuation ->
            val done = AtomicBoolean(false)
            val created = AtomicReference<TextToSpeech?>()

            fun finish(result: ReadAloudResult) {
                if (!done.compareAndSet(false, true)) return
                val tts = created.getAndSet(null)
                if (result == ReadAloudResult.FINISHED) engine = tts else tts?.shutdown()
                continuation.resume(result)
            }
            // Senza motori installati TextToSpeech chiama onInit con ERROR gia' dentro il costruttore.
            created.set(
                TextToSpeech(context) { status ->
                    val tts = created.get()
                    if (status != TextToSpeech.SUCCESS || tts == null) {
                        finish(ReadAloudResult.FAILED)
                        return@TextToSpeech
                    }
                    // La voce si prova prima della guida: una voce guasta resterebbe muta per tutto il viaggio.
                    selectVoice(tts, Locale.forLanguageTag(language), allowNetworkVoice = false, File(context.cacheDir, PROBE_FILE)) { unavailable ->
                        if (unavailable != null) {
                            finish(unavailable)
                        } else {
                            tts.setAudioAttributes(NAVIGATION_AUDIO)
                            tts.setOnUtteranceProgressListener(Listener(tts))
                            finish(ReadAloudResult.FINISHED)
                        }
                    }
                },
            )
            if (done.get()) created.getAndSet(null)?.shutdown()
            continuation.invokeOnCancellation {
                if (done.compareAndSet(false, true)) created.getAndSet(null)?.shutdown()
            }
        }
    }

    override fun say(text: String) {
        val tts = engine ?: return
        audio.requestAudioFocus(focus)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "${utterances.incrementAndGet()}")
    }

    override fun stop() {
        val tts = engine ?: return
        engine = null
        if (!tts.isSpeaking) {
            tts.shutdown()
            audio.abandonAudioFocusRequest(focus)
        }
    }

    // A fine frase la musica torna al volume di prima; un motore gia' fermato con stop() si spegne qui.
    private inner class Listener(private val tts: TextToSpeech) : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit

        override fun onDone(utteranceId: String) = ended(utteranceId)

        @Deprecated("Sostituito da onError(String, Int), chiamato ancora dai motori vecchi")
        override fun onError(utteranceId: String) = ended(utteranceId)

        override fun onError(utteranceId: String, errorCode: Int) = ended(utteranceId)

        private fun ended(utteranceId: String) {
            // Una frase interrotta da quella dopo non conta: la musica resta bassa per la nuova.
            if (utteranceId != "${utterances.get()}") return
            audio.abandonAudioFocusRequest(focus)
            if (engine !== tts) tts.shutdown()
        }
    }

    private companion object {
        val NAVIGATION_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
