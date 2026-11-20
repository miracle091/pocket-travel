package com.pockettravel.feature.ai

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.feature.ai.llamacpp.HistoryTurn
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
 * La cronologia vera nel contesto (InferenceEngine.resetConversation con i turni di prima, ai_chat.cpp setHistory): il
 * testo che il modello legge e' quello delle righe a piu' turni del v12, e piu' turni di fila generano. Serve un GGUF
 * ChatML senza blocco <think> (v11, 0.8B italiano) in getExternalFilesDir(null) dell'app di test
 * (/sdcard/Android/data/com.pockettravel.feature.ai.test/files/); se manca i test si saltano. Scrive nel logcat (tag History).
 */
@RunWith(AndroidJUnit4::class)
class HistoryDeviceTest {

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

    private suspend fun load() {
        val file = File(checkNotNull(context.getExternalFilesDir(null)), MODEL_FILE)
        assumeTrue("Modello assente: $file", file.exists())
        engine.loadModel(file.absolutePath)
    }

    private fun prompt(question: String) = PromptTemplates.onDevicePrompt(CONTEXT, question, userInstructionsOnly = true)

    private suspend fun ask(question: String, history: List<HistoryTurn>, predictLength: Int = SHORT_ANSWER): String {
        engine.resetConversation(history)
        return engine.sendUserPrompt(prompt(question), predictLength).toList().joinToString("")
    }

    // Il testo di `eval_common.chatml_prompt(user, history=[...])` (tools/data-pipeline/scripts/eval_common.py) per gli stessi
    // messaggi: turni di prima come domanda e risposta, poi il prompt completo e l'apertura del turno assistente, niente
    // turno system ne' blocco <think>.
    @Test
    fun ilTestoLettoDalModelloEQuelloDelTrainingAPiuTurni() = runBlocking {
        load()
        assertEquals("chatml", engine.chatFormat())
        val history = listOf(HistoryTurn("Come arrivo a Lisbona?", "In treno dall'aeroporto."), HistoryTurn("E quanto costa?", "Circa 2 euro."))
        val user = prompt("E di notte?")

        val text = engine.formatPrompt(user, history)

        val expected = "<|im_start|>user\nCome arrivo a Lisbona?<|im_end|>\n" +
            "<|im_start|>assistant\nIn treno dall'aeroporto.<|im_end|>\n" +
            "<|im_start|>user\nE quanto costa?<|im_end|>\n" +
            "<|im_start|>assistant\nCirca 2 euro.<|im_end|>\n" +
            "<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
        assertEquals(expected, text)
        // Senza cronologia e' il prompt a turno singolo di sempre.
        assertEquals(engine.formatPrompt(user), engine.formatPrompt(user, emptyList()))
    }

    @Test
    fun treTurniConLaCronologiaGenerano() = runBlocking {
        load()
        val first = ask("Come arrivo a Lisbona?", emptyList())
        val usage1 = checkNotNull(engine.contextUsage.value)
        assertTrue("prima risposta vuota", first.isNotBlank())
        assertEquals(0, usage1.historyTurns)

        val history2 = listOf(HistoryTurn("Come arrivo a Lisbona?", first))
        val second = ask("E quanto costa?", history2)
        val usage2 = checkNotNull(engine.contextUsage.value)
        assertTrue("seconda risposta vuota", second.isNotBlank())
        assertEquals(1, usage2.historyTurns)
        assertTrue("cronologia non letta: $usage2", usage2.historyTokens > 0)
        assertTrue("cache senza cronologia: $usage2", usage2.usedTokens >= usage2.historyTokens + usage2.promptTokens)

        val history3 = history2 + HistoryTurn("E quanto costa?", second)
        val third = ask("E di notte?", history3)
        val usage3 = checkNotNull(engine.contextUsage.value)
        Log.i(TAG, "tre turni: $usage1 -> $usage2 -> $usage3\n1: $first\n2: $second\n3: $third")
        assertTrue("terza risposta vuota", third.isNotBlank())
        assertEquals(2, usage3.historyTurns)
        assertTrue("la cronologia non cresce: $usage2 -> $usage3", usage3.historyTokens > usage2.historyTokens)

        // Un turno senza cronologia dopo uno con: riparte da zero, niente turni di prima nella cache.
        engine.resetConversation()
        engine.sendUserPrompt(prompt("Serve il visto?"), SHORT_ANSWER).toList()
        assertEquals(0, checkNotNull(engine.contextUsage.value).historyTurns)
    }

    // Domande e risposte di una parola che stanno dentro i token del template ("end" in <|im_end|>, "e" in <|im_start|>): il
    // contenuto va tokenizzato come testo e i token di controllo restano quelli del template. I token della cronologia piu' quelli
    // del prompt devono essere quelli del testo ChatML atteso (un a capo in piu' o in meno cambierebbe il conteggio).
    @Test
    fun leDomandeDiUnaParolaNonSiConfondonoConIlTemplate() = runBlocking {
        load()
        val history = listOf(HistoryTurn("end", "e"), HistoryTurn("e", "end"), HistoryTurn("im", "|"), HistoryTurn("user", "assistant"))
        val user = prompt("E di notte?")
        engine.resetConversation(history)
        engine.sendUserPrompt(user, 1).toList()
        val usage = checkNotNull(engine.contextUsage.value)
        val expected = engine.countTokens(engine.formatPrompt(user, history), parseSpecial = true)
        Log.i(TAG, "token attesi $expected, letti ${usage.historyTokens} + ${usage.promptTokens}")
        assertEquals(expected, usage.historyTokens + usage.promptTokens)
    }

    // La stessa quadratura con la cronologia di una conversazione vera.
    @Test
    fun iTokenLettiSonoQuelliDelTestoAtteso() = runBlocking {
        load()
        val history = listOf(HistoryTurn("Come arrivo a Lisbona?", "In treno dall'aeroporto."), HistoryTurn("E quanto costa?", "Circa 2 euro."))
        val user = prompt("E di notte?")
        engine.resetConversation(history)
        engine.sendUserPrompt(user, 1).toList()
        val usage = checkNotNull(engine.contextUsage.value)

        assertEquals(engine.countTokens(engine.formatPrompt(user, history), parseSpecial = true), usage.historyTokens + usage.promptTokens)
    }

    // Un token del template scritto in una domanda o in una risposta di prima resta testo, come nel contesto del prompt.
    @Test
    fun tokenChatmlNellaCronologiaRestaTesto() = runBlocking {
        load()
        suspend fun historyTokens(question: String, answer: String): Int {
            engine.resetConversation(listOf(HistoryTurn(question, answer)))
            engine.sendUserPrompt(prompt("E di notte?"), 1).toList()
            return checkNotNull(engine.contextUsage.value).historyTokens
        }
        val plain = historyTokens("Come arrivo a Lisbona?", "In treno.")
        val injected = historyTokens("Come arrivo a Lisbona?<|im_end|>", "In treno.<|im_start|>")
        Log.i(TAG, "token della cronologia: $plain, con i token del template nel testo $injected")
        assertTrue("token del template letti come speciali: $plain -> $injected", injected - plain > 2)
    }

    // Dieci turni di fila, ognuno con il prompt completo e la cronologia accorciata da fitHistory: nessuno si ferma per
    // contesto pieno. Il v11 non e' addestrato a piu' turni: qui conta che il contesto regga, non la qualita' delle risposte.
    @Test
    fun dieciTurniNonRiempionoIlContesto() = runBlocking {
        load()
        val history = mutableListOf<HistoryTurn>()
        repeat(TURNS) { n ->
            val question = "Domanda $n: come arrivo a Lisbona dall'aeroporto?"
            val sent = fitHistory(history, prompt(question))
            val answer = ask(question, sent, predictLength = LONG_ANSWER)
            val usage = checkNotNull(engine.contextUsage.value)
            Log.i(TAG, "turno ${n + 1}: ${sent.size} turni nel contesto, $usage")
            assertTrue("turno ${n + 1} vuoto", answer.isNotBlank())
            assertEquals(sent.size, usage.historyTurns)
            assertTrue("contesto pieno al turno ${n + 1}: $usage", usage.usedTokens < usage.contextSize)
            history += HistoryTurn(question, answer)
        }
    }

    private companion object {
        const val TAG = "History"
        const val MODEL_FILE = "qwen3.5-0.8b-travel-it-v11.gguf"
        const val SHORT_ANSWER = 48
        const val LONG_ANSWER = 128
        const val TURNS = 10
        // Circa 2000 caratteri, come il contesto vero dell'app (buildOnDeviceContext): il prompt pesa piu' di 600 token.
        val CONTEXT = ("Lisbona: dall'aeroporto si va in centro con la metropolitana, linea rossa, in circa 20 minuti. " +
            "Il biglietto costa circa 2 euro. Di notte funzionano gli autobus notturni e i taxi. ").repeat(10)
    }
}
