package com.pockettravel.core.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.coroutines.resume

enum class ReadAloudResult {
    FINISHED,

    // La voce della lingua manca sul dispositivo o il motore non la ha.
    LANGUAGE_UNAVAILABLE,

    // Per la lingua c'e' solo una voce che usa la rete e non e' permessa.
    ONLY_NETWORK_VOICE,

    // Nessun motore di sintesi vocale, errore o blocco del motore.
    FAILED,
}

/**
 * Lettura ad alta voce con la voce di sistema del dispositivo. Con le voci di rete non permesse, la lettura usa solo
 * voci installate e il testo resta sul telefono; con le voci di rete permesse il motore di sintesi (per esempio
 * Google) puo' scegliere una voce che manda il testo al suo servizio.
 */
interface ReadAloud {
    /**
     * Legge [text] nella lingua [language] (tag BCP 47, come "it" o "en") e ritorna a lettura finita; annullare la
     * coroutine ferma la voce. Con [allowNetworkVoice] falso sceglie solo voci installate, senza rete: se per la
     * lingua ne esistono solo di rete, ritorna [ReadAloudResult.ONLY_NETWORK_VOICE]. Sul thread principale.
     */
    suspend fun speak(text: String, language: String, allowNetworkVoice: Boolean = false): ReadAloudResult
}

class SystemReadAloud @Inject constructor(@ApplicationContext private val context: Context) : ReadAloud {

    override suspend fun speak(text: String, language: String, allowNetworkVoice: Boolean): ReadAloudResult {
        val chunks = speechChunks(text, TextToSpeech.getMaxSpeechInputLength())
        if (chunks.isEmpty()) return ReadAloudResult.FINISHED
        return suspendCancellableCoroutine { continuation ->
            // Il motore risponde dai suoi thread: done fa finire la lettura una volta sola, qualunque cosa arrivi prima.
            val done = AtomicBoolean(false)
            val engine = AtomicReference<TextToSpeech?>()
            // Se il processo del motore muore a meta' non arriva ne' onDone ne' onError: il tempo massimo chiude la lettura.
            val handler = Handler(Looper.getMainLooper())

            fun finish(result: ReadAloudResult) {
                if (!done.compareAndSet(false, true)) return
                handler.removeCallbacksAndMessages(null)
                engine.getAndSet(null)?.shutdown()
                continuation.resume(result)
            }
            val lastId = "${chunks.lastIndex}"

            // La lettura, con la voce scelta: dai thread del motore, a prova della voce finita.
            fun read(tts: TextToSpeech) {
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) = Unit

                    override fun onDone(utteranceId: String) {
                        if (utteranceId == lastId) finish(ReadAloudResult.FINISHED)
                    }

                    @Deprecated("Sostituito da onError(String, Int), chiamato ancora dai motori vecchi")
                    override fun onError(utteranceId: String) = finish(ReadAloudResult.FAILED)

                    override fun onError(utteranceId: String, errorCode: Int) = finish(ReadAloudResult.FAILED)

                    // Il motore ha scartato la frase senza leggerla (per esempio perche' e' stato fermato da fuori).
                    override fun onStop(utteranceId: String, interrupted: Boolean) = finish(ReadAloudResult.FAILED)
                })
                val queued = chunks.withIndex().all { (i, chunk) ->
                    tts.speak(chunk, TextToSpeech.QUEUE_ADD, null, "$i") == TextToSpeech.SUCCESS
                }
                if (!queued) finish(ReadAloudResult.FAILED)
            }
            // Senza motori installati TextToSpeech chiama onInit con ERROR gia' dentro il costruttore.
            engine.set(
                TextToSpeech(context) { status ->
                    val tts = engine.get()
                    if (status != TextToSpeech.SUCCESS || tts == null) {
                        finish(ReadAloudResult.FAILED)
                        return@TextToSpeech
                    }
                    selectVoice(tts, Locale.forLanguageTag(language), allowNetworkVoice, File(context.cacheDir, PROBE_FILE)) { unavailable ->
                        if (unavailable != null) finish(unavailable) else read(tts)
                    }
                },
            )
            handler.postDelayed({ finish(ReadAloudResult.FAILED) }, readTimeoutMillis(text.length))
            if (done.get()) engine.getAndSet(null)?.shutdown()
            continuation.invokeOnCancellation {
                if (done.compareAndSet(false, true)) {
                    handler.removeCallbacksAndMessages(null)
                    engine.getAndSet(null)?.shutdown()
                }
            }
        }
    }
}

/**
 * Sceglie la voce di [locale] e passa a [onSelected] null, o il motivo per cui la lingua non si puo' leggere. Ogni voce
 * si prova con una sillaba sintetizzata in [probe] (prima quella del motore se la rete e' permessa, poi le installate
 * dalla migliore): una voce che il motore dice pronta puo' non aprirsi (sull'emulatore en-US), e si passa alla seguente.
 * [onSelected] arriva dai thread del motore.
 */
internal fun selectVoice(tts: TextToSpeech, locale: Locale, allowNetworkVoice: Boolean, probe: File, onSelected: (ReadAloudResult?) -> Unit) {
    val voices = runCatching { tts.voices }.getOrNull().orEmpty()
    val choice = chooseOfflineVoice(voices.map { it.toCandidate() }, locale.language)
    val installed = (choice as? VoiceChoice.Offline)?.names.orEmpty().map { name -> voices.first { it.name == name } }
    val support = if (allowNetworkVoice) tts.setLanguage(locale) else TextToSpeech.LANG_NOT_SUPPORTED
    val engineVoice = support != TextToSpeech.LANG_MISSING_DATA && support != TextToSpeech.LANG_NOT_SUPPORTED
    // Se nessuna voce parla: senza voci della lingua "non disponibile", con le sole voci di rete non permesse lo si dice.
    val unavailable = if (choice == VoiceChoice.OnlyNetwork && !allowNetworkVoice) ReadAloudResult.ONLY_NETWORK_VOICE else ReadAloudResult.LANGUAGE_UNAVAILABLE
    val tryInstalled = { tryVoices(tts, installed, probe, unavailable, onSelected) }
    if (engineVoice) probeVoice(tts, probe) { works -> if (works) onSelected(null) else tryInstalled() } else tryInstalled()
}

private fun tryVoices(tts: TextToSpeech, voices: List<Voice>, probe: File, unavailable: ReadAloudResult, onSelected: (ReadAloudResult?) -> Unit) {
    val voice = voices.firstOrNull()
    if (voice == null || tts.setVoice(voice) != TextToSpeech.SUCCESS) {
        if (voice == null) onSelected(unavailable) else tryVoices(tts, voices.drop(1), probe, unavailable, onSelected)
        return
    }
    probeVoice(tts, probe) { works -> if (works) onSelected(null) else tryVoices(tts, voices.drop(1), probe, unavailable, onSelected) }
}

// Sintetizza una sillaba in [probe] con la voce impostata e dice se ci e' riuscita; il file si cancella subito.
private fun probeVoice(tts: TextToSpeech, probe: File, onResult: (Boolean) -> Unit) {
    fun result(works: Boolean) {
        probe.delete()
        onResult(works)
    }
    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit

        override fun onDone(utteranceId: String) = result(true)

        @Deprecated("Sostituito da onError(String, Int), chiamato ancora dai motori vecchi")
        override fun onError(utteranceId: String) = result(false)

        override fun onError(utteranceId: String, errorCode: Int) = result(false)
    })
    if (tts.synthesizeToFile(PROBE_TEXT, null, probe, PROBE_TEXT) != TextToSpeech.SUCCESS) result(false)
}

private const val PROBE_TEXT = "a"

/** File della prova della voce, nella cache dell'app: si cancella a prova finita. */
internal const val PROBE_FILE = "voice-probe.wav"

private fun Voice.toCandidate() = VoiceCandidate(
    name = name,
    language = locale.language,
    requiresNetwork = isNetworkConnectionRequired,
    installed = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features.orEmpty(),
    quality = quality,
)
