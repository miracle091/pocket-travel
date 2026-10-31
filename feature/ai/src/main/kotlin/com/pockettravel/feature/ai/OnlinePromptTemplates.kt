package com.pockettravel.feature.ai

/**
 * Template dei prompt del motore "Online" (stringhe esplicite: vedi PromptTemplates.onDevicePrompt).
 * Un messaggio system con le istruzioni e il contesto; la domanda va da sola nel messaggio user.
 */
object OnlinePromptTemplates {

    /** Il messaggio system: istruzioni e, se non e' vuoto, il [context] (blocchi di [contextBlock]). */
    fun systemPrompt(context: String, language: String = "it"): String =
        if (language == "en") {
            "You are a travel assistant.\n" +
                "Answer only on a verifiable basis; if you are not sure about visas or vaccinations,\n" +
                "tell the user to check the official source (WHO/embassy link) instead of answering." +
                if (context.isBlank()) {
                    ""
                } else {
                    "\nAnswer using the context blocks below, taken from the app's guides, and cite the section you use, " +
                        "for example (Wikivoyage, section What to see). If the context does not answer the question " +
                        "(another city, another topic or no useful context), start by saying that the information is not " +
                        "included in the app's guides; only then you may add what you know, presenting it as general " +
                        "knowledge to check.\n\n$context"
                }
        } else {
            "Sei un assistente di viaggio.\n" +
                "Rispondi solo su base verificabile; se non sei certo su visti o vaccinazioni,\n" +
                "indica di consultare la fonte ufficiale (link OMS/ambasciata) invece di rispondere." +
                if (context.isBlank()) {
                    ""
                } else {
                    "\nRispondi usando i blocchi di contesto qui sotto, presi dalle guide dell'app, e cita la sezione che usi, " +
                        "per esempio (Wikivoyage, sezione Cosa vedere). Se il contesto non risponde alla domanda (un'altra " +
                        "città, un altro argomento o nessun contesto utile), comincia dicendo che l'informazione non è " +
                        "presente nelle guide dell'app; solo dopo puoi aggiungere quello che sai, presentandolo come " +
                        "conoscenza generale da verificare.\n\n$context"
                }
        }

    /** Un blocco di contesto con la sua [source]; vuoto ([body] "") e' la sola parte fissa, che serve a contare il budget. */
    fun contextBlock(source: String, body: String, language: String = "it"): String {
        val safeSource = source.replace('"', '\'')
        // Un "</contesto>" nel testo di una guida o di una nota chiuderebbe il blocco prima del tempo.
        val safeBody = body.replace(CLOSING_TAG, "<\\\\/")
        return if (language == "en") {
            "<context source=\"$safeSource\">\n$safeBody\n</context>"
        } else {
            "<contesto fonte=\"$safeSource\">\n$safeBody\n</contesto>"
        }
    }

    private val CLOSING_TAG = Regex("</(?=\\s*conte(?:sto|xt))", RegexOption.IGNORE_CASE)

    /** La fonte di una citazione ("Fonte: Wikivoyage, sezione X — link" o "Source: ..."), senza prefisso e senza link. */
    fun sourceOf(citation: String): String =
        citation.removePrefix("Fonte: ").removePrefix("Source: ").substringBefore(" — ")
}
