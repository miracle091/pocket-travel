package com.pockettravel.feature.ai

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Misura il riconoscimento vocale sul dispositivo (SpeechRecognizer.createOnDeviceSpeechRecognizer) su file audio al posto
 * del microfono, da Android 13 (EXTRA_AUDIO_SOURCE). I file stanno in getExternalFilesDir(null)/speech: per ogni frase un
 * WAV PCM 16 bit mono a 16 kHz e una riga "nome<TAB>lingua<TAB>testo atteso" in cases.tsv (lingua "it" o "en").
 * Scrive nel logcat (tag SpeechProbe) lingue installate, testo riconosciuto, parole sbagliate e tempo:
 *   adb logcat -s SpeechProbe
 */
@RunWith(AndroidJUnit4::class)
class SpeechRecognitionDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Test
    fun riconosceLeFrasiSulDispositivo() {
        assumeTrue("Serve Android 13", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        assumeTrue("Nessun riconoscimento sul dispositivo", SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
        val dir = File(checkNotNull(context.getExternalFilesDir(null)), "speech")
        val cases = File(dir, "cases.tsv").takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.map { it.split('\t') }
        assumeTrue("Nessun cases.tsv in $dir", !cases.isNullOrEmpty())

        val recognizer = onMain { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
        try {
            for (language in listOf("it-IT", "en-US")) logSupport(recognizer, language)
            var words = 0
            var wrong = 0
            for ((name, language, expected) in checkNotNull(cases)) {
                val result = recognize(recognizer, File(dir, "$name.wav"), if (language == "it") "it-IT" else "en-US")
                val errors = wordErrors(expected, result.text)
                words += normalized(expected).size
                wrong += errors
                Log.i(TAG, "$name: testo pronto ${result.delayMs} ms dopo la fine dell'audio, $errors parole sbagliate | atteso \"$expected\" | riconosciuto \"${result.text}\"")
            }
            Log.i(TAG, "totale: $wrong parole sbagliate su $words")
        } finally {
            onMain { recognizer.destroy() }
        }
    }

    /** Con la rete: chiede il pacchetto delle due lingue al servizio del telefono, poi lo si vede tra le installate. */
    @Test
    fun scaricaLeLingue() {
        assumeTrue("Serve Android 13", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        assumeTrue("Nessun riconoscimento sul dispositivo", SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
        val recognizer = onMain { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
        try {
            for (language in listOf("it-IT", "en-US")) {
                onMain { recognizer.triggerModelDownload(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)) }
            }
            repeat(DOWNLOAD_CHECKS) {
                SystemClock.sleep(10_000)
                for (language in listOf("it-IT", "en-US")) logSupport(recognizer, language)
            }
        } finally {
            onMain { recognizer.destroy() }
        }
    }

    private fun logSupport(recognizer: SpeechRecognizer, language: String) {
        val support = CompletableFuture<String>()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        onMain {
            recognizer.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(result: RecognitionSupport) {
                    support.complete("installate ${result.installedOnDeviceLanguages}, in arrivo ${result.pendingOnDeviceLanguages}, scaricabili ${result.supportedOnDeviceLanguages}")
                }

                override fun onError(error: Int) {
                    support.complete("errore $error")
                }
            })
        }
        Log.i(TAG, "$language: ${support.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)}")
    }

    /** Testo riconosciuto e ms tra la fine dell'audio della frase e l'ultimo cambio del testo (negativo: pronto prima). */
    private data class Recognition(val text: String, val delayMs: Long)

    private fun recognize(recognizer: SpeechRecognizer, wav: File, language: String): Recognition {
        val pcm = wav.readBytes().let { it.copyOfRange(dataOffset(it), it.size) }
        val (read, write) = ParcelFileDescriptor.createPipe()
        val result = CompletableFuture<String>()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
        val speechEnd = AtomicLong()
        val lastChange = AtomicLong()
        onMain {
            // Con una sorgente audio esterna il testo arriva solo nei risultati parziali: l'onResults finale e' vuoto.
            var partial = ""
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onPartialResults(partialResults: Bundle) {
                    val text = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isNotBlank() && text != partial) {
                        partial = text
                        lastChange.set(SystemClock.elapsedRealtime())
                    }
                }

                override fun onResults(results: Bundle) {
                    result.complete(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() } ?: partial)
                }

                override fun onError(error: Int) {
                    result.complete("<errore $error>")
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            recognizer.startListening(intent)
        }
        // In tempo reale, come dal microfono, e un secondo di silenzio alla fine perche' il riconoscitore chiuda la frase.
        thread {
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                val chunk = SAMPLE_RATE * 2 / 10
                for (audio in listOf(pcm, ByteArray(SAMPLE_RATE * 2))) {
                    audio.asList().chunked(chunk).forEach {
                        out.write(it.toByteArray())
                        SystemClock.sleep(100)
                    }
                    if (speechEnd.get() == 0L) speechEnd.set(SystemClock.elapsedRealtime())
                }
            }
        }
        return try {
            Recognition(result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), lastChange.get() - speechEnd.get())
        } finally {
            read.close()
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val future = CompletableFuture<T>()
        mainHandler.post { future.complete(block()) }
        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val TAG = "SpeechProbe"
        const val SAMPLE_RATE = 16_000
        const val TIMEOUT_SECONDS = 60L
        const val DOWNLOAD_CHECKS = 18
    }
}

/** Inizio dei campioni nel WAV: dopo l'intestazione del blocco "data", la cui posizione varia con il programma che lo scrive. */
private fun dataOffset(wav: ByteArray): Int {
    var offset = 12
    while (offset + 8 <= wav.size) {
        val size = (wav[offset + 4].toInt() and 0xFF) or ((wav[offset + 5].toInt() and 0xFF) shl 8) or
            ((wav[offset + 6].toInt() and 0xFF) shl 16) or ((wav[offset + 7].toInt() and 0xFF) shl 24)
        if (String(wav, offset, 4, Charsets.US_ASCII) == "data") return offset + 8
        offset += 8 + size
    }
    error("Blocco data assente")
}

private fun normalized(text: String) = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

/** Distanza di Levenshtein sulle parole, minuscole e senza punteggiatura. */
private fun wordErrors(expected: String, actual: String): Int {
    val a = normalized(expected)
    val b = normalized(actual)
    var previous = IntArray(b.size + 1) { it }
    for (i in a.indices) {
        val current = IntArray(b.size + 1)
        current[0] = i + 1
        for (j in b.indices) {
            current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
        }
        previous = current
    }
    return previous[b.size]
}
