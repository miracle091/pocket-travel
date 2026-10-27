package com.pockettravel.core.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** La voce di sistema vera: legge fino in fondo in italiano e in inglese e si ferma quando la lettura si annulla. */
@RunWith(AndroidJUnit4::class)
class SystemReadAloudDeviceTest {

    private val readAloud = SystemReadAloud(InstrumentationRegistry.getInstrumentation().targetContext)

    private fun speak(text: String, language: String): ReadAloudResult =
        runBlocking { withContext(Dispatchers.Main) { readAloud.speak(text, language) } }

    @Test
    fun leggeFinoInFondoNelleDueLingue() {
        val italian = speak("Il museo apre alle nove.\n- Lunedì chiuso", "it")
        assumeTrue("Nessun motore di sintesi vocale", italian != ReadAloudResult.FAILED)
        assertEquals(ReadAloudResult.FINISHED, italian)
        assertEquals(ReadAloudResult.FINISHED, speak("The museum opens at nine.", "en"))
    }

    @Test
    fun siFermaQuandoSiAnnullaEPoiRiparte() {
        assumeTrue("Nessun motore di sintesi vocale", speak("Prova.", "it") == ReadAloudResult.FINISHED)
        val long = List(40) { "Questa e' una frase lunga da leggere ad alta voce." }.joinToString(" ")
        val interrupted = runBlocking { withContext(Dispatchers.Main) { withTimeoutOrNull(1_500) { readAloud.speak(long, "it") } } }
        assertNull(interrupted)
        assertEquals(ReadAloudResult.FINISHED, speak("Di nuovo.", "it"))
    }
}
