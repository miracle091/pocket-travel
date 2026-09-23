package com.pockettravel.feature.ai

/** Template dei prompt dell'assistente. */
object PromptTemplates {

    fun onDevicePrompt(context: String, question: String): String = """
        Sei una guida turistica offline.
        Rispondi in massimo 3 frasi, in italiano, usando solo le informazioni nel CONTESTO.
        Se il contesto non basta, dillo esplicitamente.

        CONTESTO: $context

        DOMANDA: $question
    """.trimIndent()
}
