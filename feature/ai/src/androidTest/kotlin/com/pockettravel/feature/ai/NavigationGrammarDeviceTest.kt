package com.pockettravel.feature.ai

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.feature.ai.NavigationRequestProfile.BIKE
import com.pockettravel.feature.ai.NavigationRequestProfile.CAR
import com.pockettravel.feature.ai.NavigationRequestProfile.WALK
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * Estrazione di meta e mezzo con la grammatica ([NAVIGATION_GRAMMAR]) sui modelli del catalogo presenti in
 * getExternalFilesDir(null) (vedi [LlamaEngineDeviceTest] per come copiarli). Verifica che l'output sia sempre JSON
 * valido e scrive nel logcat (tag NavGrammar) precisione e tempi di modello, regole e risultato finale:
 *   adb logcat -s NavGrammar
 */
@RunWith(AndroidJUnit4::class)
class NavigationGrammarDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun estraeSempreJsonValidoEMisuraLaPrecisione() = runBlocking<Unit> {
        // I modelli "pt-" (addestrati in italiano) si usano solo con l'interfaccia in italiano (LlmModelCatalog.isUsableIn).
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
        val settings = AiSettingsStore(context)
        val coordinator = AiModelCoordinator()
        val engine = OnDeviceLlmEngine(context, LlmModelManager(OkHttpClient(), modelsDir, coordinator), coordinator, settings, DeviceAiCapability(context))

        val rulesOk = CASES.count { (text, expected) -> matches(parseNavigationRequest(text), expected) }
        Log.i(TAG, "regole: $rulesOk/${CASES.size}")
        for (model in models) {
            settings.setSelectedModelId(model.id)
            engine.release()
            Log.i(TAG, "${model.id}: caricamento ${engine.ensureLoaded()} ms")
            var invalid = 0
            var modelOk = 0
            var finalOk = 0
            val times = mutableListOf<Long>()
            for ((text, expected) in CASES) {
                val start = System.currentTimeMillis()
                val rules = checkNotNull(parseNavigationRequest(text)) { "regole: $text" }
                val output = engine.generateWithGrammar(navigationPrompt(rules.destination), NAVIGATION_GRAMMAR, 64)
                times += System.currentTimeMillis() - start
                val parsed = parseNavigationJson(output)
                if (parsed == null) invalid++
                if (parsed.equals(expected.destination, ignoreCase = true)) modelOk++
                val final = mergeNavigationRequest(rules, parsed)
                if (matches(final, expected)) finalOk++ else Log.i(TAG, "  ko: \"$text\" -> $output -> $final")
            }
            times.sort()
            Log.i(
                TAG,
                "${model.id}: meta del modello $modelOk/${CASES.size}, finale $finalOk/${CASES.size}, JSON non valido $invalid, " +
                    "tempo mediano ${times[times.size / 2]} ms, massimo ${times.last()} ms",
            )
            assertEquals("JSON non valido con ${model.id}", 0, invalid)
        }
        engine.release()
    }

    private fun matches(actual: NavigationRequest?, expected: NavigationRequest) =
        actual != null && actual.destination.equals(expected.destination, ignoreCase = true) && actual.profile == expected.profile

    private companion object {
        const val TAG = "NavGrammar"

        val CASES = listOf(
            "portami al Colosseo a piedi" to NavigationRequest("Colosseo", WALK),
            "portami alla stazione centrale" to NavigationRequest("stazione centrale", null),
            "indicazioni per piazza San Marco in bici" to NavigationRequest("piazza San Marco", BIKE),
            "portami a vedere il Colosseo domani, vado a piedi" to NavigationRequest("Colosseo", WALK),
            "portami in macchina all'aeroporto" to NavigationRequest("aeroporto", CAR),
            "come arrivo al Duomo di Milano?" to NavigationRequest("Duomo di Milano", null),
            "voglio andare alla Torre Eiffel camminando" to NavigationRequest("Torre Eiffel", WALK),
            "naviga verso via Roma 12" to NavigationRequest("via Roma 12", null),
            "accompagnami al museo del Louvre per favore" to NavigationRequest("museo del Louvre", null),
            "percorso fino al Castello Sforzesco in bicicletta" to NavigationRequest("Castello Sforzesco", BIKE),
            "andiamo alla spiaggia di Jurmala con la macchina" to NavigationRequest("spiaggia di Jurmala", CAR),
            "come raggiungo il Ponte Vecchio a piedi" to NavigationRequest("Ponte Vecchio", WALK),
            "portami alla Sagrada Familia" to NavigationRequest("Sagrada Familia", null),
            "indicazioni stradali per Piazza del Campo in auto" to NavigationRequest("Piazza del Campo", CAR),
            "voglio andare al mercato centrale di Riga, a piedi per favore" to NavigationRequest("mercato centrale di Riga", WALK),
            "portami subito alla farmacia" to NavigationRequest("farmacia", null),
            "naviga fino all'hotel Danieli" to NavigationRequest("hotel Danieli", null),
            "come vado alla Basilica di San Pietro con la bici" to NavigationRequest("Basilica di San Pietro", BIKE),
            "portami al parcheggio dell'aeroporto di Fiumicino in macchina" to NavigationRequest("parcheggio dell'aeroporto di Fiumicino", CAR),
            "take me to the Colosseum on foot" to NavigationRequest("Colosseum", WALK),
            "directions to Central Station" to NavigationRequest("Central Station", null),
            "navigate to the Eiffel Tower by car" to NavigationRequest("Eiffel Tower", CAR),
            "how do I get to Times Square walking" to NavigationRequest("Times Square", WALK),
            "take me to the airport please" to NavigationRequest("airport", null),
            "I want to go to the Louvre by bike" to NavigationRequest("Louvre", BIKE),
            "route to Old Town Square" to NavigationRequest("Old Town Square", null),
            "guide me to the British Museum, I'm walking" to NavigationRequest("British Museum", WALK),
            "get me to the train station by bicycle" to NavigationRequest("train station", BIKE),
            "take me to 10 Downing Street" to NavigationRequest("10 Downing Street", null),
            "navigate to Sagrada Familia driving" to NavigationRequest("Sagrada Familia", CAR),
            "can you take me to the Brandenburg Gate on foot" to NavigationRequest("Brandenburg Gate", WALK),
            "take me to the beach tomorrow morning" to NavigationRequest("beach", null),
            "how can I get to Riga Central Market by car" to NavigationRequest("Riga Central Market", CAR),
            "please navigate to the Acropolis Museum" to NavigationRequest("Acropolis Museum", null),
        )
    }
}
