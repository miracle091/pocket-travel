package com.pockettravel.feature.ai

/** Template dei prompt della modalita' "Online" (stringhe esplicite: vedi PromptTemplates.onDevicePrompt). */
object OnlinePromptTemplates {

    fun onlinePrompt(question: String, language: String = "it"): String =
        if (language == "en") {
            "You are a travel assistant.\n" +
                "Answer only on a verifiable basis; if you are not sure about visas or vaccinations,\n" +
                "tell the user to check the official source (WHO/embassy link) instead of answering.\n\n" +
                "QUESTION: $question"
        } else {
            "Sei un assistente di viaggio.\n" +
                "Rispondi solo su base verificabile; se non sei certo su visti o vaccinazioni,\n" +
                "indica di consultare la fonte ufficiale (link OMS/ambasciata) invece di rispondere.\n\n" +
                "DOMANDA: $question"
        }
}
