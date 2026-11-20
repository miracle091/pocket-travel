package com.pockettravel.feature.ai

import com.pockettravel.feature.ai.llamacpp.HistoryTurn
import com.pockettravel.feature.ai.llamacpp.InferenceEngine

// Contesto del modello sul dispositivo: uguale a DEFAULT_CONTEXT_SIZE di ai_chat.cpp.
internal const val ON_DEVICE_CONTEXT_SIZE = 4096

// Il margine di ai_chat.cpp (OVERFLOW_HEADROOM) che non si riempie mai.
private const val CONTEXT_HEADROOM = 4

// Stima prudente: i tokenizer dei Qwen danno circa 3,5 caratteri per token in italiano e in inglese; con 3 la stima sta sopra.
private const val CHARS_PER_TOKEN = 3

// Apertura e chiusura di un turno ChatML (<|im_start|>ruolo\n ... <|im_end|>\n), e apertura del turno assistente dopo il prompt.
private const val TEMPLATE_TOKENS_PER_MESSAGE = 5
private const val TEMPLATE_TOKENS_ASSISTANT_OPENING = 3

private fun estimatedTokens(text: String) = (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN + TEMPLATE_TOKENS_PER_MESSAGE

private fun HistoryTurn.estimatedTokens() = estimatedTokens(question) + estimatedTokens(answer)

/** I turni con testo: gli altri non entrano mai nel contesto, e non contano come turni tolti. */
internal fun List<HistoryTurn>.usable(): List<HistoryTurn> = filter { it.question.isNotBlank() && it.answer.isNotBlank() }

/**
 * I turni di prima che il modello con la cronologia vera (dal v12, [LlmModelDefinition.historyInContext]) legge davanti a
 * [prompt]: domanda come l'ha scritta l'utente e risposta, mai contesto ne' istruzioni (stanno solo nel prompt). Entrano con
 * il prompt e la risposta nella soglia di compattazione ([compactionThreshold]); se non ci stanno si tolgono i turni piu'
 * vecchi dopo il primo, come le righe a piu' turni del training (`sft_multi_turn.py` tiene il primo e toglie quelli di
 * mezzo), poi il primo e solo alla fine l'ultimo. I turni senza testo si scartano. Stima dei token dai caratteri: il codice
 * nativo li conta davvero e rifiuta una cronologia che non entra nel contesto, ma qui si resta sotto con un margine.
 */
internal fun fitHistory(history: List<HistoryTurn>, prompt: String, contextSize: Int = ON_DEVICE_CONTEXT_SIZE): List<HistoryTurn> {
    val kept = history.usable().toMutableList()
    val budget = minOf(compactionThreshold(contextSize), contextSize - CONTEXT_HEADROOM - InferenceEngine.DEFAULT_PREDICT_LENGTH)
    val promptTokens = estimatedTokens(prompt) + TEMPLATE_TOKENS_ASSISTANT_OPENING
    while (kept.isNotEmpty() && promptTokens + kept.sumOf { it.estimatedTokens() } > budget) {
        kept.removeAt(if (kept.size > 2) 1 else 0)
    }
    return kept
}

/**
 * La cronologia per il prossimo turno del modello [definition]: i [turns] della conversazione, solo per i modelli con
 * [LlmModelDefinition.historyInContext]; per gli altri vuota, e il prompt resta un turno solo con la domanda unita. Vuota anche
 * con una foto ([withPhoto]) o un testo selezionato: il loro prompt sta da solo.
 */
internal fun historyForModel(
    definition: LlmModelDefinition,
    turns: List<HistoryTurn>,
    withPhoto: Boolean = false,
    selectedText: String? = null,
): List<HistoryTurn> =
    if (definition.historyInContext && !withPhoto && selectedText.isNullOrBlank()) turns else emptyList()

/**
 * Il prompt Sul dispositivo costruito da [build] con la domanda giusta: con la cronologia che entra nel contesto la domanda
 * [typed] come l'ha scritta l'utente; senza cronologia, o se [fitHistory] la scarta tutta, quella [merged] con il turno prima
 * (followUpQuestion), perche' un seguito senza i turni prima non si capirebbe.
 */
internal fun promptWithHistory(history: List<HistoryTurn>, merged: String, typed: String, build: (String) -> String): String {
    if (history.isEmpty()) return build(merged)
    val prompt = build(typed)
    return if (fitHistory(history, prompt).isEmpty()) build(merged) else prompt
}
