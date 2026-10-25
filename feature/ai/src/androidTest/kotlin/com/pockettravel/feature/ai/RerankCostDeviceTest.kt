package com.pockettravel.feature.ai

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * Costo del riordino delle sezioni col modello della chat, senza training e con il motore di oggi: per ogni domanda e
 * per ogni configurazione di CONFIGS (numero di candidate e caratteri per candidata), ogni candidata con il prompt "La sezione contiene la risposta? sì o no" e la
 * grammatica sì/no di eval_retrieval.py --rerank (RERANK_PROMPT), un token di risposta. Ogni candidata e' una
 * conversazione nuova, quindi il tempo e' il limite alto: senza le candidate in un solo batch e senza riusare il prefisso
 * comune. Misura i modelli italiani del catalogo presenti in getExternalFilesDir(null) (vedi [LlamaEngineDeviceTest]) e
 * scrive tempi e memoria (PSS dell'app di test) nel logcat:
 *   adb logcat -s RerankCost
 */
@RunWith(AndroidJUnit4::class)
class RerankCostDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    // La scelta del modello segue la lingua delle guide: con il dispositivo in inglese i modelli italiani non valgono.
    @Test
    fun misuraTempoEMemoriaDelRiordino() = runBlocking<Unit> {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ITALIAN)
        try {
            measure()
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    private suspend fun measure() {
        val modelsDir = checkNotNull(context.getExternalFilesDir(null))
        val models = LlmModelCatalog.ALL.filter { File(modelsDir, it.fileName).exists() && LlmModelCatalog.isUsableIn(it, "it") }
        assumeTrue("Nessun modello in $modelsDir", models.isNotEmpty())
        val settings = aiSettingsStore(context)
        val coordinator = AiModelCoordinator()
        val engine = LlamaOnDeviceLlmEngine(
            context, FileLlmModelManager(OkHttpClient(), modelsDir, coordinator, Dispatchers.IO), coordinator, settings,
            DeviceAiCapability(context), Dispatchers.IO,
        )
        for (model in models) {
            settings.setSelectedModelId(model.id)
            engine.release()
            Log.i(TAG, "${model.id}: caricamento ${engine.ensureLoaded()} ms, PSS ${pssMb()} MB")
            for ((count, chars) in CONFIGS) {
                val sections = sections(count, chars)
                val perQuestion = mutableListOf<Long>()
                var maxPss = 0
                for (question in QUESTIONS) {
                    val start = SystemClock.elapsedRealtime()
                    val answers = sections.map { (place, text) ->
                        engine.generateWithGrammar(prompt(question, place, text), GRAMMAR, 1).trim()
                    }
                    perQuestion += SystemClock.elapsedRealtime() - start
                    maxPss = maxOf(maxPss, pssMb())
                    Log.i(TAG, "${perQuestion.last()} ms  $question -> $answers")
                    answers.forEach { assertTrue("${model.id}: \"$it\" non e' sì o no", it in setOf("sì", "no", "s", "n")) }
                }
                Log.i(
                    TAG,
                    "${model.id}: $count candidate da $chars caratteri, mediana ${perQuestion.sorted()[perQuestion.size / 2]} " +
                        "ms a domanda, massimo ${perQuestion.max()} ms, PSS massima $maxPss MB",
                )
            }
        }
        engine.release()
    }

    private fun pssMb(): Int = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / KB_PER_MB

    private fun prompt(question: String, place: String, text: String) =
        "Domanda di un viaggiatore: $question\n\nSezione della guida ($place):\n$text\n\n" +
            "La sezione contiene la risposta alla domanda? Rispondi solo sì o no."

    private companion object {
        const val TAG = "RerankCost"
        const val KB_PER_MB = 1024
        // (candidate, caratteri per candidata): la misura di partenza e quella del criterio scelto dall'utente (al piu'
        // ~1,5 s in piu' a domanda col 0.8B).
        val CONFIGS = listOf(10 to 600, 4 to 300)
        const val GRAMMAR = "root ::= \"sì\" | \"no\""
        val QUESTIONS = listOf(
            "Dove dormire a Spalato?", "Tira vento a Spalato?", "Ci sono voli interni?", "Cosa si mangia a Zara?",
        )
        private val BODIES = listOf(
            "Il centro storico si visita a piedi in mezza giornata: il palazzo di Diocleziano, la cattedrale e le vie " +
                "strette del quartiere medievale. ",
            "Gli ostelli economici sono vicini alla stazione degli autobus, mentre gli alberghi sul lungomare costano di " +
                "piu' in estate. ",
            "La bora soffia d'inverno da nord-est, a raffiche, e puo' fermare i traghetti per qualche ora. ",
            "Dall'aeroporto partono voli nazionali per Zagabria e Dubrovnik, piu' frequenti da maggio a settembre. ",
        )

        // Il testo conta per il numero di token, non per il contenuto.
        fun sections(count: Int, chars: Int) = List(count) { i ->
            val body = BODIES[i % BODIES.size]
            "Spalato, sezione ${i + 1}" to body.repeat(chars / body.length + 1).take(chars)
        }
    }
}
