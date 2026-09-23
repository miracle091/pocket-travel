package com.pockettravel.feature.ai

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.feature.ai.llamacpp.InferenceEngine
import com.pockettravel.feature.ai.llamacpp.internal.InferenceEngineImpl
import com.pockettravel.feature.ai.llamacpp.isModelLoaded
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
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

/**
 * Esercita la libreria nativa llm-engine vera (llama.cpp) con un modello GGUF reale: i percorsi di
 * errore/recupero dell'engine non sono testabili in JVM, perche' InferenceEngineImpl carica la
 * libreria nativa alla creazione. Il modello (SmolLM2 135M, ~100 MB, lo stesso del catalogo) non
 * e' nel repo: va copiato prima in getExternalFilesDir(null), altrimenti i test vengono saltati:
 *   adb push SmolLM2-135M-Instruct-Q4_K_M.gguf /sdcard/Android/data/com.pockettravel.feature.ai.test/files/
 */
@RunWith(AndroidJUnit4::class)
class LlamaEngineDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val definition = LlmModelCatalog.ALL.first { it.id == "smollm2-135m-instruct" }
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
    fun tearDown() {
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

    // Regressione: prima del fix unload() non azzerava g_model, e un load() fallito non lo
    // sovrascriveva: il cleanUp() dello stato Error rifaceva llama_model_free sullo stesso puntatore.
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
        val settings = AiSettingsStore(context).apply { setSelectedModelId(definition.id) }
        val coordinator = AiModelCoordinator()
        val onDevice = OnDeviceLlmEngine(
            context,
            LlmModelManager(OkHttpClient(), modelsDir, coordinator),
            coordinator,
            settings,
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
