package com.pockettravel.feature.ai

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
 * Benchmark pp512/tg128 in token/s (LlmBenchmark.runTokens) su ogni modello del catalogo copiato in
 * getExternalFilesDir(null), come gli altri test con modello. Stampa il JSON dello schema comune (tag LlmBenchmarkTest) e lo
 * scrive in benchmark-<id modello>.json nella stessa cartella. Da lanciare a telefono fresco, con l'app ferma:
 *   adb shell am instrument -w -e class com.pockettravel.feature.ai.LlmBenchmarkDeviceTest <package>.test/androidx.test.runner.AndroidJUnitRunner
 * Per ripetere la misura basta rilanciarlo: il CV di ogni misura deve restare sotto il 5%.
 */
@RunWith(AndroidJUnit4::class)
class LlmBenchmarkDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun misuraPpETgInTokenAlSecondo() = runBlocking<Unit> {
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
        val settings = aiSettingsStore(context)
        val coordinator = AiModelCoordinator()
        val manager = FileLlmModelManager(OkHttpClient(), modelsDir, coordinator, Dispatchers.IO)
        val capability = DeviceAiCapability(context)
        val engine = LlamaOnDeviceLlmEngine(context, manager, coordinator, settings, capability, Dispatchers.IO)
        val benchmark = LlmBenchmark(engine, context, settings, manager, capability, Dispatchers.IO)

        for (model in models) {
            settings.setSelectedModelId(model.id)
            engine.release()
            val record = benchmark.runTokens()
            val json = record.toJson().toString()
            Log.i(TAG, json)
            File(modelsDir, "benchmark-${model.id}.json").writeText(json)
            Log.i(TAG, "${model.id}: pp ${record.pp.median} tok/s (CV ${record.pp.cvPercent}%), tg ${record.tg.median} tok/s (CV ${record.tg.cvPercent}%)")
            assertEquals(model.id, record.modelId)
            assertTrue(record.pp.median > 0 && record.tg.median > 0)
        }
        engine.release()
    }

    private companion object {
        const val TAG = "LlmBenchmarkTest"
    }
}
