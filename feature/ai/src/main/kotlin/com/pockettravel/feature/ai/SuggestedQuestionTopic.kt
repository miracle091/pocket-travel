package com.pockettravel.feature.ai

import com.pockettravel.core.data.GuideCategory

/** Gli argomenti delle domande suggerite sotto una risposta: ognuno ha un testo per lingua (strings.xml). */
internal enum class SuggestedQuestionTopic { GETTING_AROUND, LODGING, FOOD, SIGHTS, CUSTOMS, SAFETY }

private const val MAX_SUGGESTED_QUESTIONS = 3

private fun GuideCategory.suggestedQuestionTopic(): SuggestedQuestionTopic? = when (this) {
    GuideCategory.TRASPORTI -> SuggestedQuestionTopic.GETTING_AROUND
    GuideCategory.ALLOGGIO -> SuggestedQuestionTopic.LODGING
    GuideCategory.CIBO_BEVANDE -> SuggestedQuestionTopic.FOOD
    GuideCategory.COSA_VEDERE -> SuggestedQuestionTopic.SIGHTS
    GuideCategory.USI_COSTUMI, GuideCategory.DA_SAPERE -> SuggestedQuestionTopic.CUSTOMS
    GuideCategory.SICUREZZA -> SuggestedQuestionTopic.SAFETY
    GuideCategory.DOGANE, GuideCategory.SALUTE, GuideCategory.FRASI_UTILI, GuideCategory.ACQUISTI, GuideCategory.CONNETTIVITA,
    GuideCategory.VITA_QUOTIDIANA, GuideCategory.FATTI_RAPIDI, GuideCategory.STORIA, GuideCategory.CLIMA,
    -> null
}

/**
 * Fino a tre domande suggerite, una per argomento delle categorie [categories] (nell'ordine in cui compaiono: quelle
 * della guida che la risposta non ha citato), senza generare nulla. [text] da' la domanda di un argomento per [place]; mai la domanda gia' fatta ([asked]).
 * Senza luogo o senza sezioni delle guide, nessuna.
 */
internal fun suggestedQuestions(
    categories: List<GuideCategory>,
    place: String?,
    asked: List<String>,
    text: (SuggestedQuestionTopic, place: String) -> String,
): List<String> {
    if (place.isNullOrBlank()) return emptyList()
    val skipped = asked.map { it.trim().lowercase() }.toSet()
    return categories.mapNotNull { it.suggestedQuestionTopic() }.distinct()
        .map { text(it, place) }
        .filter { it.trim().lowercase() !in skipped }
        .distinct()
        .take(MAX_SUGGESTED_QUESTIONS)
}
