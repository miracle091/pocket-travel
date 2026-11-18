package com.pockettravel.feature.ai

import android.content.Context
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.feature.ai.llamacpp.InferenceEngine
import com.pockettravel.feature.ai.llamacpp.internal.InferenceEngineImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Vision sul dispositivo (mmproj di mtmd): una foto in un prompt a modello e mmproj Qwen3.5 2B. I file non sono nel repo:
 * si cercano in getExternalFilesDir(null) dell'app di test (/sdcard/Android/data/com.pockettravel.feature.ai.test/files/)
 * e il test si salta se mancano. La foto e' l'asset buskursbuch-91-92.jpg (nota sulla fonte accanto). Scrive nel logcat
 * (tag VisionTest) tempo di caricamento dell'mmproj, tempo fino al primo token (codifica della foto, decodifica del
 * prompt e primo token), tempo totale, risposta e memoria (PSS e VmHWM, il picco del processo).
 */
@RunWith(AndroidJUnit4::class)
class VisionDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var engine: InferenceEngineImpl

    @Before
    fun setUp() = runBlocking<Unit> {
        engine = InferenceEngineImpl.getInstance(context) as InferenceEngineImpl
        engine.state.first { it !is InferenceEngine.State.Uninitialized && it !is InferenceEngine.State.Initializing }
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        val state = engine.state.value
        if (state is InferenceEngine.State.ModelReady || state is InferenceEngine.State.Error) engine.cleanUp()
    }

    private fun file(name: String): File {
        val file = File(checkNotNull(context.getExternalFilesDir(null)), name)
        assumeTrue("File assente: $file", file.exists())
        return file
    }

    private fun memory(label: String) {
        val hwm = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM") }
        Log.i(TAG, "$label: PSS ${Debug.getPss() / KB_PER_MB} MB, $hwm")
    }

    @Test
    fun orarioDellaFotoDaLaValiditaDelLibro() = runBlocking {
        val model = file(MODEL)
        val mmproj = file(MMPROJ)
        val image = InstrumentationRegistry.getInstrumentation().context.assets.open(IMAGE).use { it.readBytes() }
        engine.loadModel(model.absolutePath, nThreads = THREADS, nThreadsBatch = THREADS)
        memory("modello caricato")

        val loadStart = System.nanoTime()
        engine.loadMmproj(mmproj.absolutePath)
        Log.i(TAG, "mmproj caricato in ${(System.nanoTime() - loadStart) / NANOS_PER_MS} ms")
        memory("mmproj caricato")

        engine.resetConversation()
        val start = System.nanoTime()
        var firstTokenMs = -1L
        val answer = StringBuilder()
        engine.sendUserPrompt(QUESTION, image, PREDICT_LENGTH).collect {
            if (firstTokenMs < 0) firstTokenMs = (System.nanoTime() - start) / NANOS_PER_MS
            answer.append(it)
        }
        Log.i(TAG, "primo token dopo $firstTokenMs ms, risposta completa in ${(System.nanoTime() - start) / NANOS_PER_MS} ms")
        Log.i(TAG, "risposta: $answer")
        memory("dopo la risposta")
        assertTrue("La risposta non cita il 1992: $answer", answer.contains("1992"))

        // Un prompt di solo testo dopo la foto funziona ancora: la conversazione e la cache restano coerenti.
        engine.resetConversation()
        val text = StringBuilder()
        engine.sendUserPrompt("Say hello in one short sentence.", PREDICT_LENGTH).collect { text.append(it) }
        Log.i(TAG, "dopo la foto, solo testo: $text")
        assertTrue("Risposta vuota dopo la foto", text.isNotBlank())
    }

    private companion object {
        const val TAG = "VisionTest"
        const val MODEL = "qwen3.5-2b-travel-en-v11.gguf"
        const val MMPROJ = "mmproj-qwen3.5-2b-F16.gguf"
        const val IMAGE = "buskursbuch-91-92.jpg"
        const val QUESTION = "Until when is this timetable valid? Answer briefly."
        const val PREDICT_LENGTH = 96
        const val THREADS = 4
        const val NANOS_PER_MS = 1_000_000L
        const val KB_PER_MB = 1024
    }
}
