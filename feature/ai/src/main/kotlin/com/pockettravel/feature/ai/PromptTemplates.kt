package com.pockettravel.feature.ai

/** Template dei prompt dell'assistente. */
object PromptTemplates {

    /**
     * [language]: "it" o "en", la lingua dell'interfaccia. Il testo italiano non va toccato: e' quello su
     * cui sono addestrati i modelli "Addestrati da noi" (generate_sft_dataset.py).
     */
    fun onDevicePrompt(context: String, question: String, language: String = "it"): String =
        if (language == "en") {
            """
                You are an offline travel guide.
                Answer in at most 3 sentences, in English, using only the information in the CONTEXT.
                If the context is not enough, say so explicitly.

                CONTEXT: $context

                QUESTION: $question
            """.trimIndent()
        } else {
            """
                Sei una guida turistica offline.
                Rispondi in massimo 3 frasi, in italiano, usando solo le informazioni nel CONTESTO.
                Se il contesto non basta, dillo esplicitamente.

                CONTESTO: $context

                DOMANDA: $question
            """.trimIndent()
        }

    /** Contesto quando la guida non ha nulla sulla domanda. */
    fun emptyContext(language: String = "it"): String =
        if (language == "en") "No information available for this region." else "Nessuna informazione disponibile per questa regione."
}
