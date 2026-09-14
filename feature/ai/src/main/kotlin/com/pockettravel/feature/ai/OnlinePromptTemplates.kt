package com.pockettravel.feature.ai

/** Template "Online" dalla sezione "Esempi di prompt AI efficaci" della specifica tecnica. */
object OnlinePromptTemplates {

    fun onlinePrompt(question: String): String = """
        Sei un assistente di viaggio.
        Rispondi solo su base verificabile; se non sei certo su visti o vaccinazioni,
        indica di consultare la fonte ufficiale (link OMS/ambasciata) invece di rispondere.

        DOMANDA: $question
    """.trimIndent()
}
