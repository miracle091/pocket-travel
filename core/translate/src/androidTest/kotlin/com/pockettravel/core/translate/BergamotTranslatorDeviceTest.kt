package com.pockettravel.core.translate

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * La coppia di modelli italiano verso inglese di Firefox Translations (base-memory) traduce davvero. I file non sono
 * nel repository: il test li cerca in getExternalFilesDir(null)/bergamot/iten dell'app di test
 * (/sdcard/Android/data/com.pockettravel.core.translate.test/files/bergamot/iten/: model.iten.intgemm.alphas.bin,
 * vocab.iten.spm, lex.50.50.iten.s2t.bin) e si salta se mancano. Va copiata con adb root: come shell la cartella
 * non e' scrivibile. Si lancia con installDebugAndroidTest e am instrument, mai con connectedDebugAndroidTest: disinstalla
 * l'app di test e cancella i modelli copiati.
 */
@RunWith(AndroidJUnit4::class)
class BergamotTranslatorDeviceTest {

    private val directory = File(
        checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)),
        "bergamot/iten",
    )

    // VmRSS e VmHWM (picco) del processo di test, in MB: la misura della memoria del traduttore.
    private fun memory(): String {
        val values = File("/proc/self/status").readLines().filter { it.startsWith("VmRSS") || it.startsWith("VmHWM") }
        return values.joinToString(" ") { line -> line.split(Regex("\\s+")).let { "${it[0]}${it[1].toLong() / 1024} MB" } }
    }

    private fun load(): BergamotTranslator {
        val model = File(directory, "model.iten.intgemm.alphas.bin")
        val vocabulary = File(directory, "vocab.iten.spm")
        val shortlist = File(directory, "lex.50.50.iten.s2t.bin")
        assumeTrue("Modelli bergamot assenti in $directory", model.isFile && vocabulary.isFile && shortlist.isFile)
        return runBlocking { BergamotTranslator.load(model, vocabulary, shortlist) }
    }

    @Test
    fun traduceDaItalianoAInglese() {
        Log.i("BergamotTest", "Prima del caricamento: ${memory()}")
        val translator = load()
        Log.i("BergamotTest", "Dopo il caricamento: ${memory()}")
        translator.use {
            val sentences = listOf(
                "Il museo apre alle nove e chiude alle diciotto.",
                "Dove posso comprare un biglietto per il treno? Costa 🙂 poco?",
            )
            for (sentence in sentences) {
                val start = System.nanoTime()
                val translated = runBlocking { translator.translate(sentence) }
                Log.i("BergamotTest", "${(System.nanoTime() - start) / 1_000_000} ms: $sentence -> $translated")
                assertTrue("Traduzione vuota", translated.isNotBlank())
                assertNotEquals(sentence, translated)
            }
            Log.i("BergamotTest", "Dopo le traduzioni: ${memory()}")
        }
    }

    // Due modelli insieme (le due direzioni dell'app) e un nuovo caricamento dopo il rilascio: i logger di marian
    // hanno un nome fisso e crearne uno gia' esistente fa abortire il processo.
    @Test
    fun duePerVolta_ePoiDiNuovoDopoIlRilascio() {
        val first = load()
        val second = load()
        runBlocking {
            assertNotEquals("Il museo apre alle nove.", first.translate("Il museo apre alle nove."))
            assertNotEquals("Il museo apre alle nove.", second.translate("Il museo apre alle nove."))
        }
        first.close()
        second.close()
        load().use { again ->
            assertTrue(runBlocking { again.translate("Il museo apre alle nove.") }.isNotBlank())
        }
    }

    @Test
    fun ilTestoVuotoRestaVuotoEDopoLaChiusuraSiRifiuta() {
        val translator = load()
        assertEquals("", runBlocking { translator.translate("") })
        translator.close()
        translator.close()
        val failure = runCatching { runBlocking { translator.translate("Ciao") } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun conUnFileMancanteLanciaIOException() {
        val failure = runCatching {
            runBlocking { BergamotTranslator.load(File(directory, "assente.bin"), File(directory, "assente.spm")) }
        }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
    }
}
