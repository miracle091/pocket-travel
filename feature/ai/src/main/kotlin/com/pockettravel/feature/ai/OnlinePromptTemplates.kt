package com.pockettravel.feature.ai

/** Template dei prompt della modalita' "Online". */
object OnlinePromptTemplates {

    fun onlinePrompt(question: String, language: String = "it"): String =
        if (language == "en") {
            """
                You are a travel assistant.
                Answer only on a verifiable basis; if you are not sure about visas or vaccinations,
                tell the user to check the official source (WHO/embassy link) instead of answering.

                QUESTION: $question
            """.trimIndent()
        } else {
            """
                Sei un assistente di viaggio.
                Rispondi solo su base verificabile; se non sei certo su visti o vaccinazioni,
                indica di consultare la fonte ufficiale (link OMS/ambasciata) invece di rispondere.

                DOMANDA: $question
            """.trimIndent()
        }
}
