package com.pockettravel.feature.ai

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * Categoria della domanda (modelQuestionCategory) con i modelli italiani presenti in getExternalFilesDir(null) (vedi
 * [LlamaEngineDeviceTest] per come copiarli): la grammatica si compila, la risposta e' sempre un nome di categoria e la
 * stessa domanda da' sempre la stessa (campionamento greedy con la grammatica). Scrive i tempi nel logcat:
 *   adb logcat -s ModelCategory
 */
@RunWith(AndroidJUnit4::class)
class ModelQuestionCategoryDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun rispondeSempreConUnaCategoriaEStabile() = runBlocking<Unit> {
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
        val engine = LlamaOnDeviceLlmEngine(context, FileLlmModelManager(OkHttpClient(), modelsDir, coordinator, Dispatchers.IO), coordinator, settings, DeviceAiCapability(context), Dispatchers.IO)
        val names = modelCategoryLabels("it").map { it.name }
        for (model in models) {
            settings.setSelectedModelId(model.id)
            engine.release()
            Log.i(TAG, "${model.id}: caricamento ${engine.ensureLoaded()} ms")
            val times = mutableListOf<Long>()
            for (question in QUESTIONS) {
                val answers = List(2) {
                    val start = SystemClock.elapsedRealtime()
                    engine.generateWithGrammar(modelCategoryPrompt(question, "it"), modelCategoryGrammar("it"), 8).trim()
                        .also { times += SystemClock.elapsedRealtime() - start }
                }
                Log.i(TAG, "${times.takeLast(2)} ms  $question -> ${answers.first()}")
                assertTrue("${model.id}: \"${answers.first()}\" non e' una categoria", answers.first() in names)
                assertEquals("${model.id}: $question", answers.first(), answers.last())
            }
            Log.i(TAG, "${model.id}: tempo mediano ${times.sorted()[times.size / 2]} ms, massimo ${times.max()} ms")
        }
        engine.release()
    }

    private companion object {
        const val TAG = "ModelCategory"
        val QUESTIONS = listOf(
            "C'è un agriturismo a Zara?", "Tira vento a Spalato?", "Ci sono voli interni?", "Dove si parcheggia a Lucca?",
            "Che importanza aveva Siena nel Rinascimento?", "C'è copertura 4G?", "Come si combatte il jet lag?",
            "Quali sono i posti più belli di Porto?",
        )
    }
}
