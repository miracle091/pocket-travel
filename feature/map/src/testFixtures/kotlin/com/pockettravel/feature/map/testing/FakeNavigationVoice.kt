package com.pockettravel.feature.map.testing

import com.pockettravel.core.speech.NavigationVoice
import com.pockettravel.core.speech.ReadAloudResult

/** Voce della navigazione finta: ricorda le frasi dette e se e' accesa; [startResult] e' l'esito dell'accensione. */
class FakeNavigationVoice : NavigationVoice {
    var startResult = ReadAloudResult.FINISHED
    var started = false
        private set
    val said = mutableListOf<String>()

    override suspend fun start(language: String): ReadAloudResult {
        started = startResult == ReadAloudResult.FINISHED
        return startResult
    }

    override fun say(text: String) {
        if (started) said += text
    }

    override fun stop() {
        started = false
    }
}
