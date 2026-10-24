package com.pockettravel.feature.ai

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.feature.ai.llamacpp.InferenceEngine
import com.pockettravel.feature.ai.llamacpp.internal.InferenceEngineImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Formato dei prompt scelto da ai_chat.cpp al caricamento del modello: per la stessa domanda il testo che riceve il
 * modello deve essere quello del training (ChatML del formatter CHATML di llama.cpp, piu' il blocco <think> vuoto per i
 * template con enable_thinking: chatml_prompt e uses_empty_think in tools/data-pipeline/scripts/eval_common.py), e un
 * modello non ChatML deve passare dal suo template jinja. I GGUF non sono nel repo: ogni test cerca il suo in
 * getExternalFilesDir(null) dell'app di test (/sdcard/Android/data/com.pockettravel.feature.ai.test/files/) e si salta
 * se manca. Scrive formato e testo nel logcat (tag ChatTemplate).
 */
@RunWith(AndroidJUnit4::class)
class ChatTemplateDeviceTest {

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

    private suspend fun load(fileName: String) {
        val file = File(checkNotNull(context.getExternalFilesDir(null)), fileName)
        assumeTrue("Modello assente: $file", file.exists())
        engine.loadModel(file.absolutePath)
    }

    private suspend fun assertFormat(fileName: String, expectedFormat: String, expectedText: String) {
        load(fileName)
        val format = engine.chatFormat()
        val text = engine.formatPrompt(PROMPT)
        Log.i(TAG, "$fileName: $format\n$text")
        assertEquals(fileName, expectedFormat, format)
        assertEquals(fileName, expectedText, text)
    }

    @Test
    fun modelloV10RiceveIlChatmlConIlThinkVuoto() = runBlocking {
        assertFormat("qwen3.5-0.8b-travel-it-Q4_K_M.gguf", "chatml-think", chatml(PROMPT) + EMPTY_THINK)
    }

    // Addestrato dalla copia solo testo (text_only.py): ChatML senza enable_thinking, quindi senza blocco <think>.
    @Test
    fun modelloV11RiceveIlChatmlSenzaThink() = runBlocking {
        assertFormat(V11_FILE, "chatml", chatml(PROMPT))
    }

    @Test
    fun ufficialeQwen35RiceveIlChatmlConIlThinkVuoto() = runBlocking {
        assertFormat("Qwen3.5-0.8B-UD-Q4_K_XL.gguf", "chatml-think", chatml(PROMPT) + EMPTY_THINK)
    }

    // Gestisce i blocchi <think> ma il template non ha enable_thinking: niente blocco vuoto.
    @Test
    fun ufficialeQwen3Instruct2507RiceveIlChatml() = runBlocking {
        assertFormat("Qwen3-4B-Instruct-2507-UD-Q4_K_XL.gguf", "chatml", chatml(PROMPT))
    }

    // llama.cpp formatterebbe Gemma anche senza jinja, ma non e' ChatML: deve usare il template del GGUF.
    @Test
    fun modelloNonChatmlPassaDaJinja() = runBlocking {
        assertFormat(GEMMA_FILE, "jinja", "<start_of_turn>user\n$PROMPT<end_of_turn>\n<start_of_turn>model\n")
        val answer = engine.sendUserPrompt(PROMPT, SHORT_ANSWER).toList().joinToString("")
        Log.i(TAG, "risposta: $answer")
        assertTrue("risposta vuota", answer.isNotBlank())
    }

    // Il taglio dei prompt troppo lunghi tiene apertura e suffisso del template anche con jinja.
    @Test
    fun promptTroppoLungoConJinjaVieneTagliato() = runBlocking {
        load(GEMMA_FILE)
        val long = PromptTemplates.onDevicePrompt("Visti e passaporti. ".repeat(LONG_REPEATS), QUESTION)
        val answer = engine.sendUserPrompt(long, SHORT_ANSWER).toList().joinToString("")
        val usage = checkNotNull(engine.contextUsage.value)
        Log.i(TAG, "prompt lungo: $usage -> $answer")
        // Tagliato al contesto meno i 4 token di margine di ai_chat.cpp (OVERFLOW_HEADROOM).
        assertEquals("prompt non tagliato al contesto: $usage", usage.contextSize - 4, usage.promptTokens)
        assertTrue("risposta vuota", answer.isNotBlank())
    }

    private companion object {
        const val TAG = "ChatTemplate"
        const val V11_FILE = "qwen3.5-0.8b-travel-it-tx-qlora-Q4_K_M.gguf"
        const val GEMMA_FILE = "gemma-3-270m-it-Q8_0.gguf"
        const val EMPTY_THINK = "<think>\n\n</think>\n\n"
        const val SHORT_ANSWER = 32
        // ~4 token per ripetizione: oltre i 4096 del contesto.
        const val LONG_REPEATS = 1_200
        const val QUESTION = "Serve il visto per il Giappone?"
        val PROMPT = PromptTemplates.onDevicePrompt(
            "Visti: i cittadini italiani possono entrare in Giappone senza visto per turismo fino a 90 giorni. " +
                "Serve il passaporto valido per tutta la durata del soggiorno.",
            QUESTION,
        )

        fun chatml(user: String) = "<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
    }
}
