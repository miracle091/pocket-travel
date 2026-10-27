package com.pockettravel.core.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.coroutines.resume

enum class ReadAloudResult {
    FINISHED,

    // La voce della lingua manca sul dispositivo o il motore non la ha.
    LANGUAGE_UNAVAILABLE,

    // Nessun motore di sintesi vocale o errore del motore.
    FAILED,
}

/** Lettura ad alta voce con la voce di sistema del dispositivo: nessuna voce di rete, il testo resta sul telefono. */
interface ReadAloud {
    /**
     * Legge [text] nella lingua [language] (tag BCP 47, come "it" o "en") e ritorna a lettura finita; annullare la
     * coroutine ferma la voce. Sul thread principale.
     */
    suspend fun speak(text: String, language: String): ReadAloudResult
}

class SystemReadAloud @Inject constructor(@ApplicationContext private val context: Context) : ReadAloud {

    override suspend fun speak(text: String, language: String): ReadAloudResult {
        val chunks = speechChunks(text, TextToSpeech.getMaxSpeechInputLength())
        if (chunks.isEmpty()) return ReadAloudResult.FINISHED
        return suspendCancellableCoroutine { continuation ->
            // Il motore risponde dai suoi thread: done fa finire la lettura una volta sola, qualunque cosa arrivi prima.
            val done = AtomicBoolean(false)
            val engine = AtomicReference<TextToSpeech?>()
            fun finish(result: ReadAloudResult) {
                if (!done.compareAndSet(false, true)) return
                engine.getAndSet(null)?.shutdown()
                continuation.resume(result)
            }
            val lastId = "${chunks.lastIndex}"
            // Senza motori installati TextToSpeech chiama onInit con ERROR gia' dentro il costruttore.
            engine.set(
                TextToSpeech(context) { status ->
                    val tts = engine.get()
                    if (status != TextToSpeech.SUCCESS || tts == null) {
                        finish(ReadAloudResult.FAILED)
                        return@TextToSpeech
                    }
                    val support = tts.setLanguage(Locale.forLanguageTag(language))
                    if (support == TextToSpeech.LANG_MISSING_DATA || support == TextToSpeech.LANG_NOT_SUPPORTED) {
                        finish(ReadAloudResult.LANGUAGE_UNAVAILABLE)
                        return@TextToSpeech
                    }
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String) = Unit

                        override fun onDone(utteranceId: String) {
                            if (utteranceId == lastId) finish(ReadAloudResult.FINISHED)
                        }

                        @Deprecated("Sostituito da onError(String, Int), chiamato ancora dai motori vecchi")
                        override fun onError(utteranceId: String) = finish(ReadAloudResult.FAILED)

                        override fun onError(utteranceId: String, errorCode: Int) = finish(ReadAloudResult.FAILED)
                    })
                    val queued = chunks.withIndex().all { (i, chunk) ->
                        tts.speak(chunk, TextToSpeech.QUEUE_ADD, null, "$i") == TextToSpeech.SUCCESS
                    }
                    if (!queued) finish(ReadAloudResult.FAILED)
                },
            )
            if (done.get()) engine.getAndSet(null)?.shutdown()
            continuation.invokeOnCancellation {
                if (done.compareAndSet(false, true)) engine.getAndSet(null)?.shutdown()
            }
        }
    }
}
