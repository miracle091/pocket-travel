package com.pockettravel.feature.ai

/** Lunghezza massima del testo selezionato in un'altra app ("Chiedi all'assistente"): il resto si taglia. */
const val MAX_SELECTED_TEXT_CHARS = 1000

/** Il testo selezionato mostrato nel foglio; [truncated] dice che quello ricevuto era piu' lungo del limite. */
data class SelectedText(val text: String, val truncated: Boolean = false)

/** Il testo ricevuto da un'altra app, senza spazi ai bordi e entro [MAX_SELECTED_TEXT_CHARS]; null se vuoto o di soli spazi. */
internal fun prepareSelectedText(raw: CharSequence?): SelectedText? {
    val text = raw?.toString()?.trim().orEmpty()
    if (text.isEmpty()) return null
    val cut = cutWithoutSplitting(text, MAX_SELECTED_TEXT_CHARS)
    return SelectedText(cut.trimEnd(), truncated = cut.length < text.length)
}

/** Il testo selezionato modificato nel foglio: entro [MAX_SELECTED_TEXT_CHARS] e non piu' "tagliato". */
internal fun editedSelectedText(value: String) = SelectedText(cutWithoutSplitting(value, MAX_SELECTED_TEXT_CHARS))

/**
 * Il testo selezionato in testa a [context]: sul dispositivo il contesto (senza sezioni della guida), online la domanda. Sul
 * dispositivo nel contesto e non dopo la domanda: il prompt chiede di rispondere solo dal contesto, e lo 0.8B con il testo dopo la
 * domanda rispondeva dalle sezioni ("Traduci" su "pour" diventava un volo da Parigi a Nizza). Senza «» nel testo, così non può
 * chiudere le virgolette.
 */
internal fun contextWithSelectedText(context: String, selectedText: String?, language: String): String {
    if (selectedText.isNullOrBlank()) return context
    val selected = "${if (language == "en") "Text selected by the user" else "Testo selezionato dall'utente"}: «${selectedText.filterNot { it == '«' || it == '»' }}»"
    return if (context.isBlank()) selected else "$selected\n\n$context"
}
