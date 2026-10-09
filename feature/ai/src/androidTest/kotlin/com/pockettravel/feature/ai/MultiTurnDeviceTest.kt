package com.pockettravel.feature.ai

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.feature.ai.llamacpp.ContextUsage
import com.pockettravel.feature.ai.llamacpp.InferenceEngine
import com.pockettravel.feature.ai.llamacpp.internal.InferenceEngineImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * Conversazioni a piu' turni con un modello vero (domanda e chiarimento, cambio di destinazione, domanda
 * interrotta, richiesta di navigazione): ogni turno deve partire da zero, senza history ne' KV cache del
 * turno prima, perche' i modelli sono addestrati su un turno singolo (OnDeviceLlmEngine azzera la
 * conversazione a ogni domanda). Lo si verifica dall'uso del contesto: a fine turno la KV cache contiene
 * solo prompt e risposta di quel turno. Usa il primo modello italiano del catalogo presente in
 * getExternalFilesDir(null) (vedi [LlamaEngineDeviceTest] per come copiarlo); scrive le risposte nel
 * logcat (tag MultiTurn) per leggerle:
 *   adb logcat -s MultiTurn
 */
@RunWith(AndroidJUnit4::class)
class MultiTurnDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var engine: OnDeviceLlmEngine
    private lateinit var native: InferenceEngine

    // I modelli "pt-" (addestrati in italiano) si usano solo con l'interfaccia in italiano (LlmModelCatalog.isUsableIn).
    private val previousLocale = Locale.getDefault()

    @Before
    fun setUp() {
        Locale.setDefault(Locale.ITALIAN)
        val modelsDir = checkNotNull(context.getExternalFilesDir(null))
        val model = LlmModelCatalog.ALL.firstOrNull { File(modelsDir, it.fileName).exists() && LlmModelCatalog.isUsableIn(it, "it") }
        assumeTrue("Nessun modello italiano in $modelsDir", model != null)
        val settings = AiSettingsStore(context).apply { setSelectedModelId(checkNotNull(model).id) }
        val coordinator = AiModelCoordinator()
        engine = OnDeviceLlmEngine(context, LlmModelManager(OkHttpClient(), modelsDir, coordinator), coordinator, settings, DeviceAiCapability(context))
        native = InferenceEngineImpl.getInstance(context)
        Log.i(TAG, "modello: ${model?.id}")
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        if (::engine.isInitialized) engine.release()
        Locale.setDefault(previousLocale)
    }

    private suspend fun turn(name: String, question: String, context: String): String {
        val answer = engine.generate(PromptTemplates.onDevicePrompt(context, question))
        val usage = checkNotNull(native.contextUsage.value)
        Log.i(TAG, "$name: \"$question\" -> \"${answer.trim()}\" ($usage)")
        assertTrue("$name: risposta vuota", answer.isNotBlank())
        assertOnlyThisTurn(name, usage)
        return answer
    }

    // Nella KV cache solo prompt e risposta del turno (+1 per il token di fine turno aggiunto a n_predict).
    private fun assertOnlyThisTurn(name: String, usage: ContextUsage) {
        val extra = usage.usedTokens - usage.promptTokens - usage.generatedTokens
        assertTrue("$name: la cache contiene $extra token di turni precedenti ($usage)", extra in 0..1)
    }

    @Test
    fun domandaEPoiChiarimento() = runBlocking<Unit> {
        turn("chiarimento 1", "Serve il visto per il Giappone?", JAPAN)
        val alone = turn("chiarimento 2", "E per quanto tempo posso restare?", JAPAN)
        Log.i(TAG, "chiarimento da solo: cita i 90 giorni = ${"90" in alone}")
        // Come lo manda TravelAssistant: il seguito unito alla domanda prima.
        val joined = followUpQuestion("Serve il visto per il Giappone?", "E per quanto tempo posso restare?", emptyList())
        val answer = turn("chiarimento unito", joined, JAPAN)
        Log.i(TAG, "chiarimento unito: cita i 90 giorni = ${"90" in answer}")
    }

    @Test
    fun cambioDiDestinazione() = runBlocking<Unit> {
        turn("destinazione 1", "Cosa vedere a Roma?", ROME)
        val answer = turn("destinazione 2", "Cosa vedere a Lisbona?", LISBON)
        Log.i(TAG, "destinazione: nomina ancora Roma = ${"Roma" in answer}")
        assertFalse("la risposta su Lisbona parla dei luoghi di Roma: $answer", "Colosseo" in answer || "Pantheon" in answer)
    }

    @Test
    fun domandaInterrottaEPoiUnAltra() = runBlocking<Unit> {
        val partial = engine.generateStream(PromptTemplates.onDevicePrompt(ROME, "Cosa vedere a Roma?")).take(3).toList()
        Log.i(TAG, "interrotta: ${partial.joinToString("")}")
        assertEquals(InferenceEngine.State.ModelReady, native.state.value)
        turn("dopo l'interruzione", "Cosa vedere a Lisbona?", LISBON)
    }

    @Test
    fun navigazioneEPoiDomanda() = runBlocking<Unit> {
        val json = engine.generateWithGrammar(navigationPrompt("Colosseo"), NAVIGATION_GRAMMAR, NAVIGATION_TOKENS)
        assertNotNull("JSON non valido: $json", parseNavigationJson(json))
        val answer = turn("dopo la navigazione", "Serve il visto per il Giappone?", JAPAN)
        assertFalse("la grammatica della navigazione e' rimasta attiva: $answer", answer.trimStart().startsWith("{"))
    }

    @Test
    fun navigazioneInterrottaEPoiDomanda() = runBlocking<Unit> {
        // Annullata mentre il modello scrive il JSON: la grammatica va tolta anche cosi'.
        val job = launch { engine.generateWithGrammar(navigationPrompt("Colosseo"), NAVIGATION_GRAMMAR, NAVIGATION_TOKENS) }
        withTimeoutOrNull(CANCEL_AFTER_MS) { native.state.first { it is InferenceEngine.State.Generating } }
        job.cancel()
        job.join()
        val answer = turn("dopo la navigazione interrotta", "Serve il visto per il Giappone?", JAPAN)
        assertFalse("la grammatica della navigazione e' rimasta attiva: $answer", answer.trimStart().startsWith("{"))
    }

    /**
     * Lo stato a piu' turni del motore nativo, che l'app non usa (azzera a ogni domanda) ma ai_chat.cpp tiene:
     * senza reset, a ogni turno la KV cache cresce di quanto il turno ha aggiunto, e un turno interrotto resta
     * chiuso con la sua risposta parziale. Regressione: prima la si toglieva con llama_memory_seq_rm, che con la
     * memoria ricorrente di Qwen3.5 fallisce, e il prompt successivo falliva ("inconsistent sequence positions").
     */
    @Test
    fun motoreNativoSenzaResetTieneLaCacheCoerente() = runBlocking<Unit> {
        engine.ensureLoaded()
        native.resetConversation()
        val first = native.sendUserPrompt(PromptTemplates.onDevicePrompt(JAPAN, "Serve il visto per il Giappone?"), SHORT_ANSWER).toList()
        val afterFirst = checkNotNull(native.contextUsage.value)
        Log.i(TAG, "nativo 1: ${first.joinToString("")} ($afterFirst)")
        assertOnlyThisTurn("nativo 1", afterFirst)

        // Due pezzi ricevuti: take li interrompe subito dopo il secondo (meno di due: finito da solo, su EOG).
        val interrupted = native.sendUserPrompt("E per quanto tempo posso restare?", SHORT_ANSWER).take(2).toList()
        assertEquals("il secondo turno e' finito prima dell'interruzione", 2, interrupted.size)
        val afterCancel = checkNotNull(native.contextUsage.value)
        Log.i(TAG, "nativo 2 interrotto ($afterCancel)")

        val third = native.sendUserPrompt("E per quanto tempo posso restare?", SHORT_ANSWER).toList()
        val afterThird = checkNotNull(native.contextUsage.value)
        Log.i(TAG, "nativo 3: ${third.joinToString("")} ($afterThird)")
        // Il turno interrotto resta nella cache con il token di fine turno: il terzo parte da li'.
        val expectedStart = afterCancel.usedTokens + 1
        val extra = afterThird.usedTokens - expectedStart - afterThird.promptTokens - afterThird.generatedTokens
        assertTrue("cache del terzo turno disallineata di $extra token ($afterFirst, $afterCancel, $afterThird)", extra in 0..1)
        assertTrue("terzo turno vuoto", third.joinToString("").isNotBlank())
    }

    /**
     * Senza reset, un prompt che non entra nel contesto rimasto viene rifiutato: il motore azzera la conversazione,
     * e la domanda dopo funziona. Prima il messaggio rifiutato restava nella history senza essere nella cache, e
     * ogni prompt successivo veniva rifiutato finche' non si chiamava resetConversation.
     */
    @Test
    fun promptRifiutatoAzzeraLaConversazione() = runBlocking<Unit> {
        engine.ensureLoaded()
        native.resetConversation()
        // Un primo turno che riempie il contesto (il prompt troppo lungo viene tagliato all'inizio).
        native.sendUserPrompt("Visti e passaporti. ".repeat(FILL_REPEATS), SHORT_ANSWER).toList()
        Log.i(TAG, "contesto pieno: ${native.contextUsage.value}")

        val refused = runCatching { native.sendUserPrompt("Serve il visto per il Giappone?", SHORT_ANSWER).toList() }
        // Rifiutato solo con la memoria ricorrente (Qwen3.5), che non si sposta: con un modello che sposta il
        // contesto il secondo prompt entra, e il test non dice niente.
        assumeTrue("il modello sposta il contesto: il prompt non viene rifiutato", refused.isFailure)

        val answer = native.sendUserPrompt(PromptTemplates.onDevicePrompt(JAPAN, "Serve il visto per il Giappone?"), SHORT_ANSWER).toList()
        val usage = checkNotNull(native.contextUsage.value)
        Log.i(TAG, "dopo il rifiuto: ${answer.joinToString("")} ($usage)")
        assertTrue("risposta vuota dopo il rifiuto", answer.joinToString("").isNotBlank())
        assertOnlyThisTurn("dopo il rifiuto", usage)
    }

    private companion object {
        // ~4 token per ripetizione: oltre i 4096 del contesto.
        const val FILL_REPEATS = 1_200
        const val TAG = "MultiTurn"
        const val NAVIGATION_TOKENS = 64
        const val SHORT_ANSWER = 96
        const val CANCEL_AFTER_MS = 30_000L

        const val JAPAN = "Visti: i cittadini italiani possono entrare in Giappone senza visto per turismo fino a 90 giorni. " +
            "Serve il passaporto valido per tutta la durata del soggiorno."
        const val ROME = "Cosa vedere: il Colosseo, il Foro Romano, il Pantheon e i Musei Vaticani. " +
            "Trastevere e' il quartiere piu' animato la sera."
        const val LISBON = "Cosa vedere: la Torre di Belem, il Monastero dos Jeronimos, il quartiere dell'Alfama " +
            "e il tram 28 che sale al Castello di San Giorgio."
    }
}
