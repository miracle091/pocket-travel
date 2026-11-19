package com.pockettravel.feature.ai

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.feature.ai.llamacpp.InferenceEngine
import com.pockettravel.feature.ai.llamacpp.internal.InferenceEngineImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Risposte del 2B sul dispositivo al set di foto etichettate (tools/data-pipeline/vision/photo-set.json), da valutare sul
 * PC con vision_eval.py --score-answers. Legge vision-set/questions.jsonl (id, lang, question, image) in
 * getExternalFilesDir(null) dell'app di test (/sdcard/Android/data/com.pockettravel.feature.ai.test/files/) e aggiunge una
 * riga per risposta a vision-set/answers.jsonl (id, lang, model, answer, seconds), saltando le domande gia' risposte. Come
 * l'app: foto ridotta da preparePhoto a LOCAL_PHOTO_LONG_SIDE, la domanda da sola nel prompt, una conversazione nuova per
 * foto, i parametri di campionamento di LlamaOnDeviceLlmEngine. Ogni lingua usa il suo modello
 * (qwen3.5-2b-travel-<lang>-v11.gguf) o, se manca, quello inglese. Senza i file il test si salta.
 */
@RunWith(AndroidJUnit4::class)
class VisionSetDeviceTest {

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

    @Test
    fun risposteSulSetDiFoto() = runBlocking {
        val root = checkNotNull(context.getExternalFilesDir(null))
        val dir = File(root, "vision-set")
        val questions = File(dir, "questions.jsonl")
        val mmproj = File(root, MMPROJ)
        val fallback = File(root, modelName("en"))
        assumeTrue("File assenti in $root", questions.isFile && mmproj.isFile && fallback.isFile)
        val answers = File(dir, "answers.jsonl")
        val done = if (answers.isFile) answers.readLines().filter { it.isNotBlank() }.map { JSONObject(it).key() }.toSet() else emptySet()
        val byModel = questions.readLines().filter { it.isNotBlank() }.map(::JSONObject).filter { it.key() !in done }
            .groupBy { File(root, modelName(it.getString("lang"))).takeIf(File::isFile) ?: fallback }
        for ((model, items) in byModel) {
            if (engine.state.value is InferenceEngine.State.ModelReady) engine.cleanUp()
            engine.loadModel(model.absolutePath, topK = TOP_K, topP = TOP_P, nThreads = THREADS, speculativeNgram = true)
            engine.loadMmproj(mmproj.absolutePath)
            for (item in items) {
                val image = File(dir, item.getString("image"))
                val photo = preparePhoto({ image.inputStream() }, LOCAL_PHOTO_LONG_SIDE)
                engine.resetConversation()
                val start = System.nanoTime()
                val answer = StringBuilder()
                engine.sendUserPrompt(item.getString("question"), photo).collect { answer.append(it) }
                val seconds = (System.nanoTime() - start) / NANOS_PER_S
                val row = JSONObject().put("id", item.getString("id")).put("lang", item.getString("lang"))
                    .put("model", model.name).put("answer", answer.toString()).put("seconds", seconds)
                answers.appendText(row.toString() + "\n")
                Log.i(TAG, row.toString())
            }
        }
    }

    private fun JSONObject.key() = getString("id") + "/" + getString("lang")

    private fun modelName(lang: String) = "qwen3.5-2b-travel-$lang-v11.gguf"

    private companion object {
        const val TAG = "VisionSetTest"
        const val MMPROJ = "mmproj-qwen3.5-2b-F16.gguf"
        // Come LlamaOnDeviceLlmEngine (TOP_K, TOP_P; ngram speculativo per i modelli addestrati).
        const val TOP_K = 10
        const val TOP_P = 0.95f
        const val THREADS = 4
        const val NANOS_PER_S = 1e9
    }
}
