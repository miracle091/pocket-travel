package com.pockettravel.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSelectionTest {

    private fun voice(
        name: String,
        language: String = "it",
        network: Boolean = false,
        installed: Boolean = true,
        quality: Int = 300,
    ) = VoiceCandidate(name, language, network, installed, quality)

    @Test
    fun `le voci offline della lingua dalla qualita' piu' alta, da provare in ordine`() {
        val voices = listOf(
            voice("it-rete", network = true, quality = 500),
            voice("it-bassa", quality = 200),
            voice("it-alta", quality = 400),
            voice("en-alta", language = "en", quality = 500),
        )
        assertEquals(VoiceChoice.Offline(listOf("it-alta", "it-bassa")), chooseOfflineVoice(voices, "it"))
    }

    @Test
    fun `una voce offline non installata non vale`() {
        assertEquals(VoiceChoice.None, chooseOfflineVoice(listOf(voice("it-manca", installed = false)), "it"))
    }

    @Test
    fun `con solo voci di rete lo dice, senza voci della lingua e' nessuna`() {
        val voices = listOf(voice("it-rete", network = true), voice("en-locale", language = "en"))
        assertEquals(VoiceChoice.OnlyNetwork, chooseOfflineVoice(voices, "it"))
        assertEquals(VoiceChoice.None, chooseOfflineVoice(voices, "fr"))
        assertEquals(VoiceChoice.None, chooseOfflineVoice(emptyList(), "it"))
    }

    @Test
    fun `il tempo massimo cresce con la lunghezza del testo`() {
        assertTrue(readTimeoutMillis(10_000) > readTimeoutMillis(100))
        assertTrue(readTimeoutMillis(0) > 0)
    }
}
