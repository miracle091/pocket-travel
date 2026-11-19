package com.pockettravel.feature.ai

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.feature.ai.llamacpp.InferenceEngine
import com.pockettravel.feature.ai.llamacpp.internal.InferenceEngineImpl
import com.pockettravel.feature.ai.llamacpp.isModelLoaded
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Esercita la libreria nativa llm-engine vera (llama.cpp) con un modello GGUF reale: i percorsi di
 * errore/recupero dell'engine non sono testabili in JVM, perche' InferenceEngineImpl carica la
 * libreria nativa alla creazione. Il modello (Qwen3.5 0.8B, ~530 MB, il piu' piccolo del catalogo dei modelli) non
 * e' nel repo: va copiato prima in getExternalFilesDir(null), altrimenti i test vengono saltati:
 *   adb push Qwen3.5-0.8B-UD-Q4_K_XL.gguf /sdcard/Android/data/com.pockettravel.feature.ai.test/files/
 */
@RunWith(AndroidJUnit4::class)
class LlamaEngineDeviceTest {

    private companion object {
        const val TAG = "LlamaEngineDeviceTest"
    }

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val definition = LlmModelCatalog.ALL.first { it.id == "qwen3.5-0.8b" }
    private lateinit var modelsDir: File
    private lateinit var modelPath: String
    private lateinit var engine: InferenceEngine

    @Before
    fun setUp() = runBlocking<Unit> {
        modelsDir = checkNotNull(context.getExternalFilesDir(null))
        modelPath = File(modelsDir, definition.fileName).absolutePath
        assumeTrue("Modello di test assente: $modelPath", File(modelPath).exists())
        engine = InferenceEngineImpl.getInstance(context)
        // L'init della libreria nativa e' asincrona (llamaScope.launch in InferenceEngineImpl).
        engine.state.first { it !is InferenceEngine.State.Uninitialized && it !is InferenceEngine.State.Initializing }
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        // Singleton di processo condiviso tra i test: ogni test riparte da Initialized.
        if (::engine.isInitialized) {
            val state = engine.state.value
            if (state is InferenceEngine.State.ModelReady || state is InferenceEngine.State.Error) engine.cleanUp()
        }
    }

    private suspend fun ask(prompt: String): String {
        engine.resetConversation()
        return engine.sendUserPrompt(prompt, predictLength = 32).toList().joinToString("")
    }

    private fun garbageFile(): File =
        File(context.cacheDir, "not-a-model.gguf").apply { writeText("questo non e' un GGUF") }

    @Test
    fun caricaUnModelloGgufRealeEGeneraTesto() = runBlocking<Unit> {
        engine.loadModel(modelPath)
        assertEquals(InferenceEngine.State.ModelReady, engine.state.value)

        val answer = ask("What is the capital of Italy?")

        assertTrue("risposta vuota", answer.isNotBlank())
        assertEquals(InferenceEngine.State.ModelReady, engine.state.value)
    }

    // Lo Stop durante la lettura del prompt ferma la decodifica entro un batch (512 token), non alla fine dei ~3000 token;
    // la domanda successiva riceve una risposta normale.
    @Test
    fun stopDuranteLaLetturaDelPromptRitornaEntroUnBatch() = runBlocking<Unit> {
        engine.loadModel(modelPath)
        val longPrompt = (1..220).joinToString("\n") { "Il monumento numero $it di Roma e' molto antico e famoso." }

        // Prima lettura a vuoto: quella a freddo (pesi non ancora in memoria) e' piu' lenta e falserebbe il confronto.
        engine.resetConversation()
        engine.sendUserPrompt(longPrompt, predictLength = 1).toList()
        engine.resetConversation()
        val fullStart = System.nanoTime()
        engine.sendUserPrompt(longPrompt, predictLength = 1).toList()
        val fullMs = (System.nanoTime() - fullStart) / 1_000_000
        Log.i(TAG, "lettura completa: $fullMs ms (${engine.contextUsage.value?.promptTokens} token)")
        assumeTrue("lettura troppo veloce per misurare lo Stop: $fullMs ms", fullMs > 2_000)

        engine.resetConversation()
        val job = launch(Dispatchers.Default) { engine.sendUserPrompt(longPrompt, predictLength = 32).toList() }
        // Lo Stop deve arrivare a lettura cominciata, non prima.
        engine.state.first { it is InferenceEngine.State.ProcessingUserPrompt }
        delay(200)
        val stopStart = System.nanoTime()
        job.cancelAndJoin()
        val stopMs = (System.nanoTime() - stopStart) / 1_000_000
        Log.i(TAG, "ritorno dopo lo Stop: $stopMs ms")
        assertEquals(InferenceEngine.State.ModelReady, engine.state.value)
        assertTrue("Stop dopo $stopMs ms, lettura completa $fullMs ms", stopMs < fullMs / 3)

        val answerStart = System.nanoTime()
        val answer = ask("What is the capital of Italy?")
        Log.i(TAG, "risposta dopo lo Stop: ${(System.nanoTime() - answerStart) / 1_000_000} ms")
        assertTrue("risposta vuota dopo lo Stop", answer.isNotBlank())
    }

    @Test
    fun dopoUnFileNonGgufLoStatoErrorSiRecuperaConCleanUp() = runBlocking<Unit> {
        try {
            engine.loadModel(garbageFile().absolutePath)
            fail("un file non GGUF deve far fallire loadModel")
        } catch (_: Exception) {
        }
        assertTrue(engine.state.value is InferenceEngine.State.Error)

        engine.cleanUp()
        assertEquals(InferenceEngine.State.Initialized, engine.state.value)

        engine.loadModel(modelPath)
        assertTrue(ask("Hello").isNotBlank())
    }

    // Regressione: unload() deve azzerare g_model e un load() fallito deve sovrascriverlo, altrimenti il
    // cleanUp() dello stato Error rifarebbe llama_model_free sullo stesso puntatore.
    @Test
    fun unloadDueVolteDiSeguitoNonFaDoubleFree() = runBlocking<Unit> {
        engine.loadModel(modelPath)
        engine.cleanUp()
        try {
            engine.loadModel(garbageFile().absolutePath)
            fail("un file non GGUF deve far fallire loadModel")
        } catch (_: Exception) {
        }

        engine.cleanUp()

        engine.loadModel(modelPath)
        assertTrue(ask("Hello").isNotBlank())
    }

    @Test
    fun onDeviceLlmEngineEsceDaErrorAllaRichiestaSuccessiva() = runBlocking<Unit> {
        val settings = aiSettingsStore(context).apply { setSelectedModelId(definition.id) }
        val coordinator = AiModelCoordinator()
        val onDevice = LlamaOnDeviceLlmEngine(
            context,
            FileLlmModelManager(OkHttpClient(), modelsDir, coordinator, Dispatchers.IO),
            coordinator,
            settings,
            DeviceAiCapability(context),
            Dispatchers.IO,
        )
        try {
            engine.loadModel(garbageFile().absolutePath)
        } catch (_: Exception) {
        }
        assertTrue(engine.state.value is InferenceEngine.State.Error)

        val answer = onDevice.generate("What is the capital of Italy?")

        assertTrue("risposta vuota", answer.isNotBlank())
        assertTrue(engine.state.value.isModelLoaded)
    }
}
