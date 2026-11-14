package com.pockettravel.feature.map

import android.content.res.Configuration
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Le frasi unite della guida vocale ("Gira a sinistra in Via Roma, poi gira a destra") con la voce di sistema vera,
 * installata sul telefono: il motore le legge fino in fondo e la frase unita dura piu' della sola prima svolta.
 * I file WAV restano in `Android/data/<pacchetto del test>/files/voice/`, da ascoltare dopo il test.
 */
@RunWith(AndroidJUnit4::class)
class JoinedVoicePhraseDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val left = TurnInstruction(TurnType.LEFT, distanceToNextMeters = 30.0, pointIndex = 1)
    private val right = TurnInstruction(TurnType.RIGHT, distanceToNextMeters = 300.0, pointIndex = 2)
    private val arrive = TurnInstruction(TurnType.ARRIVE, distanceToNextMeters = 0.0, pointIndex = 3)

    @Test
    fun italiano() = check("it")

    @Test
    fun inglese() = check("en")

    private fun check(language: String) {
        val locale = Locale.forLanguageTag(language)
        val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
        val texts = ContextVoiceTexts(localized)
        val single = voicePhrase(VoiceCue.Now(left, "Via Roma"), texts)
        val joined = voicePhrase(VoiceCue.Now(left, "Via Roma", then = right), texts)
        val prepared = voicePhrase(VoiceCue.Prepare(left, 48.0, null, then = arrive), texts)
        val dir = File(context.getExternalFilesDir(null), "voice").apply { mkdirs() }
        Speaker(locale).use { speaker ->
            val singleSeconds = speaker.wavSeconds(single, File(dir, "$language-sola.wav"))
            val joinedSeconds = speaker.wavSeconds(joined, File(dir, "$language-unita.wav"))
            val preparedSeconds = speaker.wavSeconds(prepared, File(dir, "$language-preavviso-unito.wav"))
            assertTrue("\"$joined\": $joinedSeconds s, \"$single\": $singleSeconds s", joinedSeconds > singleSeconds + MIN_THEN_SECONDS)
            assertTrue("\"$prepared\": $preparedSeconds s", preparedSeconds in MIN_THEN_SECONDS..MAX_PHRASE_SECONDS)
        }
    }

    /** Il motore di sistema con una voce installata (non di rete) della lingua; il test si salta se non c'e'. */
    private inner class Speaker(locale: Locale) : AutoCloseable {
        private val ready = CountDownLatch(1)
        private var status = TextToSpeech.ERROR
        private val tts = TextToSpeech(context) { status = it; ready.countDown() }

        init {
            ready.await(INIT_SECONDS, TimeUnit.SECONDS)
            assumeTrue("Nessun motore di sintesi vocale", status == TextToSpeech.SUCCESS)
            // Una voce installata puo' essere guasta (sull'emulatore en-US non si apre): si prende la migliore che parla.
            val voice = tts.voices.orEmpty().filter { it.locale.language == locale.language && !it.isNetworkConnectionRequired && !it.notInstalled() }
                .sortedByDescending { it.quality }
                .firstOrNull { tts.voice = it; synthesize("Ok", File(context.cacheDir, "probe.wav")) }
            assumeTrue("Nessuna voce installata che parli ${locale.language}", voice != null)
            Log.i(TAG, "Voce ${locale.language}: ${voice?.name}")
        }

        private fun synthesize(text: String, file: File): Boolean {
            val done = CountDownLatch(1)
            var failed = false
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit

                override fun onDone(utteranceId: String) = done.countDown()

                @Deprecated("Sostituito da onError(String, Int), chiamato ancora dai motori vecchi")
                override fun onError(utteranceId: String) {
                    failed = true
                    done.countDown()
                }
            })
            file.delete()
            return tts.synthesizeToFile(text, null, file, text) == TextToSpeech.SUCCESS && done.await(SYNTH_SECONDS, TimeUnit.SECONDS) && !failed
        }

        fun wavSeconds(text: String, file: File): Double {
            assertTrue("\"$text\" non letta", synthesize(text, file))
            return wavDuration(file)
        }

        override fun close() = tts.shutdown()
    }

    private fun Voice.notInstalled() = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in features.orEmpty()

    // Intestazione WAV PCM: canali al byte 22, campioni al secondo al 24, bit per campione al 34, dati dal 44.
    private fun wavDuration(file: File): Double = RandomAccessFile(file, "r").use { wav ->
        fun littleEndian(offset: Long, bytes: Int): Int {
            wav.seek(offset)
            return (0 until bytes).sumOf { wav.read() shl (8 * it) }
        }
        val bytesPerSecond = littleEndian(24, 4) * littleEndian(22, 2) * littleEndian(34, 2) / 8
        (file.length() - WAV_HEADER_BYTES).toDouble() / bytesPerSecond
    }

    private companion object {
        const val TAG = "JoinedVoicePhrase"
        const val INIT_SECONDS = 10L
        const val SYNTH_SECONDS = 30L
        const val WAV_HEADER_BYTES = 44
        const val MIN_THEN_SECONDS = 0.5
        const val MAX_PHRASE_SECONDS = 10.0
    }
}
