package com.pockettravel.feature.ai

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.feature.ai.llamacpp.InferenceEngine
import com.pockettravel.feature.ai.llamacpp.internal.InferenceEngineImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
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
 * Decodifica speculativa ngram di ai_chat.cpp (generation_step): con le stesse domande e la scelta greedy, le risposte
 * con il ngram acceso devono essere quelle del campionamento un token alla volta, e arrivare prima. Il 0.8B ha la
 * memoria ricorrente, quindi le ipotesi scartate passano dal ripristino dello stato (rollback_draft); il 4B addestrato
 * (Qwen3, sola KV cache) prova il ramo con llama_memory_seq_rm, con `-e model qwen3-4b-instruct-2507-travel-it-Q4_K_M.gguf`
 * e un emulatore con 12 GB di memoria. Greedy con
 * topK = 1 (una grammatica che accetta ogni testo darebbe temperatura 0, ma la si controlla su tutto il vocabolario a
 * ogni token: minuti per risposta). Il GGUF del 0.8B italiano va in
 * getExternalFilesDir(null) dell'app di test (/sdcard/Android/data/com.pockettravel.feature.ai.test/files/); senza, i
 * test si saltano. Tempi e risposte nel logcat: `adb logcat -s SpeculativeNgram`.
 */
@RunWith(AndroidJUnit4::class)
class SpeculativeNgramDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var engine: InferenceEngineImpl
    private lateinit var modelFile: File

    @Before
    fun setUp() = runBlocking<Unit> {
        engine = InferenceEngineImpl.getInstance(context) as InferenceEngineImpl
        engine.state.first { it !is InferenceEngine.State.Uninitialized && it !is InferenceEngine.State.Initializing }
        val fileName = InstrumentationRegistry.getArguments().getString("model") ?: MODEL_FILE
        modelFile = File(checkNotNull(context.getExternalFilesDir(null)), fileName)
        assumeTrue("Modello assente: $modelFile", modelFile.exists())
    }

    @After
    fun tearDown() = runBlocking<Unit> { unload() }

    @Test
    fun stesseRisposteGreedyEPiuVeloci() = runBlocking {
        val plain = answers(speculative = false)
        val ngram = answers(speculative = true)
        val same = plain.indices.count { plain[it].text == ngram[it].text }
        plain.indices.forEach {
            Log.i(TAG, "domanda $it: ${plain[it].millis} ms senza, ${ngram[it].millis} ms con il ngram, uguali ${plain[it].text == ngram[it].text}")
            Log.i(TAG, "senza: ${plain[it].text}")
            Log.i(TAG, "con:   ${ngram[it].text}")
        }
        val plainMillis = plain.sumOf { it.millis }
        val ngramMillis = ngram.sumOf { it.millis }
        Log.i(TAG, "totale: $plainMillis ms senza, $ngramMillis ms con il ngram; risposte uguali $same su ${plain.size}")
        // La verifica in batch puo' arrotondare i logit in modo diverso dal token singolo: al massimo una risposta diversa.
        assertTrue("risposte uguali solo $same su ${plain.size}", same >= plain.size - 1)
        assertTrue("con il ngram $ngramMillis ms, senza $plainMillis ms", ngramMillis < plainMillis)
    }

    // Dopo una risposta interrotta con token accettati e non ancora restituiti, il motore torna pronto e la domanda dopo
    // da' la stessa risposta di prima dell'interruzione.
    @Test
    fun dopoUnaRispostaInterrottaLaDomandaDopoNonCambia() = runBlocking {
        load(speculative = true)
        val fresh = engine.sendUserPrompt(prompt(QUESTIONS[0]), ANSWER_TOKENS).toList().joinToString("")
        engine.resetConversation()
        engine.sendUserPrompt(prompt(QUESTIONS[1]), ANSWER_TOKENS).take(INTERRUPT_AFTER).toList()
        assertEquals(InferenceEngine.State.ModelReady, engine.state.value)
        engine.resetConversation()
        val again = engine.sendUserPrompt(prompt(QUESTIONS[0]), ANSWER_TOKENS).toList().joinToString("")
        assertEquals(fresh, again)
    }

    private data class Answer(val text: String, val millis: Long)

    private suspend fun answers(speculative: Boolean): List<Answer> {
        load(speculative)
        val result = QUESTIONS.map { question ->
            engine.resetConversation()
            val start = System.nanoTime()
            val text = engine.sendUserPrompt(prompt(question), ANSWER_TOKENS).toList().joinToString("")
            Answer(text, (System.nanoTime() - start) / NANOS_PER_MS)
        }
        unload()
        return result
    }

    // Un thread per core, al massimo 4 come sul telefono: sull'x86 i thread di ggml aspettano in attesa attiva, e con
    // piu' thread che core un token costa decine di secondi.
    private suspend fun load(speculative: Boolean) = engine.loadModel(
        modelFile.absolutePath,
        topK = 1,
        nThreads = Runtime.getRuntime().availableProcessors().coerceAtMost(MAX_THREADS),
        speculativeNgram = speculative,
    )

    private suspend fun unload() {
        val state = engine.state.value
        if (state is InferenceEngine.State.ModelReady || state is InferenceEngine.State.Error) engine.cleanUp()
    }

    private fun prompt(question: String) = PromptTemplates.onDevicePrompt(GUIDE, question)

    private companion object {
        const val TAG = "SpeculativeNgram"
        const val MODEL_FILE = "qwen3.5-0.8b-travel-it-Q4_K_M.gguf"
        const val ANSWER_TOKENS = 160
        const val INTERRUPT_AFTER = 5
        const val NANOS_PER_MS = 1_000_000L
        const val MAX_THREADS = 4

        val GUIDE = "Trastevere: quartiere sulla riva destra del Tevere, con vicoli acciottolati, trattorie e la " +
            "basilica di Santa Maria in Trastevere, famosa per i mosaici dorati del XII secolo. La sera i locali di " +
            "piazza di Santa Maria e di via della Lungaretta sono pieni fino a tarda notte. Come arrivare: tram 8 da " +
            "piazza Venezia, fermata Belli; a piedi da Campo de' Fiori attraverso ponte Sisto in dieci minuti. " +
            "Mercato di Porta Portese: la domenica mattina, dalle 6 alle 14, lungo via Portuense, con abiti usati, " +
            "antiquariato e oggetti di ogni tipo; attenzione ai borseggiatori. Gianicolo: colle sopra Trastevere " +
            "con la vista migliore su Roma; a mezzogiorno un cannone spara un colpo a salve dal piazzale Garibaldi."

        val QUESTIONS = listOf(
            "Come arrivo a Trastevere da piazza Venezia?",
            "Quando apre il mercato di Porta Portese?",
            "Cosa vedere nella basilica di Santa Maria in Trastevere?",
            "Cosa succede a mezzogiorno sul Gianicolo?",
            "Dove si esce la sera a Trastevere?",
        )
    }
}
