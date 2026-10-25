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
 * Conversione UTF-8 di ai_chat.cpp tra Kotlin e il modello: il prompt passa da UTF-16 a UTF-8 (jstring_to_utf8), la
 * risposta torna da UTF-8 a UTF-16 un token alla volta (take_complete_chars e utf8_to_utf16), con i caratteri spezzati
 * tra due token tenuti da parte fino al token successivo. Emoji fuori dal piano base (coppie di surrogate in Java),
 * CJK e accenti composti devono tornare identici, senza U+FFFD ne' surrogate spaiate, anche fermandosi a meta'
 * carattere. Va eseguito con CheckJNI attivo (`adb shell setprop debug.checkjni 1`; l'app di test e' debuggable, quindi
 * lo e' gia'). Il GGUF del 0.8B italiano va in getExternalFilesDir(null) dell'app di test
 * (/sdcard/Android/data/com.pockettravel.feature.ai.test/files/); senza, i test si saltano.
 */
@RunWith(AndroidJUnit4::class)
class Utf8DeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var engine: InferenceEngineImpl

    @Before
    fun setUp() = runBlocking<Unit> {
        engine = InferenceEngineImpl.getInstance(context) as InferenceEngineImpl
        engine.state.first { it !is InferenceEngine.State.Uninitialized && it !is InferenceEngine.State.Initializing }
        val file = File(checkNotNull(context.getExternalFilesDir(null)), MODEL_FILE)
        assumeTrue("Modello assente: $file", file.exists())
        engine.loadModel(file.absolutePath)
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        val state = engine.state.value
        if (state is InferenceEngine.State.ModelReady || state is InferenceEngine.State.Error) engine.cleanUp()
    }

    @Test
    fun testoTokenizzatoTornaIdentico() = runBlocking {
        assertEquals(TEXT, engine.streamTokens(TEXT, Int.MAX_VALUE))
    }

    // Ogni prefisso di token da' un prefisso del testo senza caratteri tagliati, come una generazione interrotta; almeno
    // un token non aggiunge nulla, quindi il testo ha davvero caratteri spezzati tra due token.
    @Test
    fun generazioneInterrottaNonTagliaICaratteri() = runBlocking {
        var previous = ""
        var tokens = 0
        var splitTokens = 0
        while (previous != TEXT) {
            tokens++
            assertTrue("il testo non torna in $tokens token", tokens <= TEXT.length * 4)
            val partial = engine.streamTokens(TEXT, tokens)
            assertWellFormed(partial)
            assertTrue("dopo $tokens token: \"$partial\" non e' un prefisso del testo", TEXT.startsWith(partial))
            assertTrue("dopo $tokens token il testo si accorcia", partial.length >= previous.length)
            if (partial == previous) splitTokens++
            previous = partial
        }
        Log.i(TAG, "$tokens token, $splitTokens con un carattere spezzato")
        assertTrue("nessun carattere spezzato tra due token", splitTokens > 0)
    }

    // La generazione vera, controllo di base: nessun pezzo che arriva a Kotlin spezza una coppia di surrogate, e dopo
    // un'interruzione il motore torna pronto. U+FFFD qui non si controlla: campionando, il modello puo' scrivere byte
    // che non formano UTF-8 valido. I caratteri spezzati tra due token li prova generazioneInterrottaNonTagliaICaratteri.
    @Test
    fun rispostaDelModelloSenzaSurrogateSpaiate() = runBlocking {
        val prompt = "Ripeti esattamente questo testo, senza aggiungere altro: $TEXT"
        val pieces = engine.sendUserPrompt(prompt, ANSWER_TOKENS).toList()
        Log.i(TAG, "risposta: ${pieces.joinToString("")}")
        pieces.forEach { assertWellFormed(it, allowReplacement = true) }
        engine.resetConversation()
        engine.sendUserPrompt(prompt, ANSWER_TOKENS).take(INTERRUPT_AFTER).toList()
        assertEquals(InferenceEngine.State.ModelReady, engine.state.value)
    }

    private fun assertWellFormed(text: String, allowReplacement: Boolean = false) {
        assertTrue("U+FFFD in \"$text\"", allowReplacement || '�' !in text)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate()) {
                assertTrue("surrogata alta spaiata in \"$text\"", i + 1 < text.length && text[i + 1].isLowSurrogate())
                i++
            } else {
                assertTrue("surrogata bassa spaiata in \"$text\"", !c.isLowSurrogate())
            }
            i++
        }
    }

    private companion object {
        const val TAG = "Utf8"
        const val MODEL_FILE = "qwen3.5-0.8b-travel-it-Q4_K_M.gguf"
        const val ANSWER_TOKENS = 96
        const val INTERRUPT_AFTER = 3

        // Emoji e simboli fuori dal piano base, una bandiera (due code point), CJK, coreano, cirillico e accenti
        // composti (lettera piu' accento combinante).
        const val TEXT = "Emoji 🦩🧭 bandiera 🇮🇹, simboli 𝄞 𓀀 𠜎, 東京と北京, 서울, Москва, é à ñ."
    }
}
