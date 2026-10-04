package com.pockettravel.feature.ai

/** Template dei prompt dell'assistente. */
object PromptTemplates {

    /**
     * [language]: "it" o "en", la lingua dell'interfaccia. Il testo italiano non va toccato: e' quello su
     * cui sono addestrati i modelli "Addestrati da noi" (generate_sft_dataset.py). Stringhe esplicite, non un raw
     * string con trimIndent(): il contesto ha righe senza rientro e trimIndent() dopo l'interpolazione lascerebbe
     * nel prompt il rientro del sorgente.
     */
    fun onDevicePrompt(context: String, question: String, language: String = "it"): String =
        if (language == "en") {
            "You are an offline travel guide.\n" +
                "Answer in at most 3 sentences, in English, using only the information in the CONTEXT.\n" +
                "If the context is not enough, say so explicitly.\n\n" +
                "CONTEXT: $context\n\n" +
                "QUESTION: $question"
        } else {
            "Sei una guida turistica offline.\n" +
                "Rispondi in massimo 3 frasi, in italiano, usando solo le informazioni nel CONTESTO.\n" +
                "Se il contesto non basta, dillo esplicitamente.\n\n" +
                "CONTESTO: $context\n\n" +
                "DOMANDA: $question"
        }

    /** Contesto quando la guida non ha nulla sulla domanda. */
    fun emptyContext(language: String = "it"): String =
        if (language == "en") "No information available for this region." else "Nessuna informazione disponibile per questa regione."
}
