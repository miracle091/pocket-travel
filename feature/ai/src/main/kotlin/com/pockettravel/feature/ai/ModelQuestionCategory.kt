package com.pockettravel.feature.ai

import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * La categoria di [question] scelta dal modello sul dispositivo, per le domande in cui le liste di parole
 * (questionGroups) non ne riconoscono nessuna ("Ci sono voli interni?", "Dove trovo un B&B a X?"): un gruppo di
 * CategoryWords, o null per il viaggio in generale. L'output e' vincolato ai nomi delle categorie (modelCategoryGrammar),
 * cosi' la risposta e' sempre una di loro, e la scelta e' greedy (ai_chat.cpp); gli esempi tra parentesi nel prompt aiutano il
 * modello piccolo. Se la classificazione fallisce la ricerca procede senza categoria.
 * Replicata in tools/data-pipeline/scripts/eval_retrieval.py (--model-categories), che un test confronta con questa.
 */
internal suspend fun modelQuestionCategory(engine: OnDeviceLlmEngine, question: String, language: String): String? {
    val labels = modelCategoryLabels(language)
    // La categoria serve solo ad allargare la ricerca: se la classificazione fallisce (grammatica non valida, motore non
    // pronto, prompt non elaborato) si cerca senza; la cancellazione invece si propaga.
    val answer = try {
        engine.generateWithGrammar(modelCategoryPrompt(question, language), modelCategoryGrammar(language), MODEL_CATEGORY_TOKENS)
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("SwallowedException") e: IllegalArgumentException) {
        null
    } catch (@Suppress("SwallowedException") e: IllegalStateException) {
        null
    } catch (@Suppress("SwallowedException") e: IOException) {
        null
    }
    return labels.firstOrNull { it.name == answer?.trim() }?.group
}

/** Una categoria come la vede il modello: nome (unica risposta ammessa), esempi nel prompt e gruppo di CategoryWords. */
internal class ModelCategoryLabel(val name: String, val examples: String?, val group: String?)

internal fun modelCategoryLabels(language: String): List<ModelCategoryLabel> = if (language == "en") modelCategoryLabelsEn else modelCategoryLabelsIt

internal fun modelCategoryPrompt(question: String, language: String): String {
    val labels = modelCategoryLabels(language).joinToString("; ") { if (it.examples == null) it.name else "${it.name} (${it.examples})" }
    return if (language == "en") {
        "Classify a traveller's question into exactly one category among: $labels.\nQuestion: $question\nCategory:"
    } else {
        "Classifica la domanda di un viaggiatore in una sola categoria tra: $labels.\nDomanda: $question\nCategoria:"
    }
}

internal fun modelCategoryGrammar(language: String): String =
    "root ::= " + modelCategoryLabels(language).joinToString(" | ") { "\"${it.name}\"" }

/** Gruppi che, dalle liste o dal modello, fanno valere la domanda come domanda di storia o clima. */
internal val HISTORY_CLIMATE_GROUPS = setOf("STORIA", "CLIMA", "PERIODI", "METEO")

/** Domanda di storia o clima per la ricerca: dalle sue parole, o (con le liste accese) dai gruppi delle liste o del modello. */
internal fun isHistoryOrClimateSearch(question: String, wordLists: Boolean, modelGroups: Set<String>): Boolean =
    isHistoryOrClimateQuestion(question) || wordLists && (questionGroups(question) + modelGroups).any { it in HISTORY_CLIMATE_GROUPS }

/**
 * Con la categoria del modello ([modelGroups] non vuoto), quante frasi della query FTS vengono dalla domanda ([baseQuery]):
 * le parole della categoria sono in fondo (withCategoryWords), e il confronto con la guida generale conta solo queste.
 */
internal fun questionPhrases(baseQuery: String, modelGroups: Set<String>): Int? =
    if (modelGroups.isEmpty()) null else baseQuery.split(" OR ").count { it.isNotBlank() }

private const val MODEL_CATEGORY_TOKENS = 8

private val modelCategoryLabelsIt = listOf(
    ModelCategoryLabel("alloggio", "hotel, dormire, campeggio", "ALLOGGIO"),
    ModelCategoryLabel("cibo", "ristoranti, piatti, bere", "CIBO_BEVANDE"),
    ModelCategoryLabel("cosa vedere", "monumenti, musei, gite", "COSA_VEDERE"),
    ModelCategoryLabel("acquisti", "negozi, prezzi, soldi", "ACQUISTI"),
    ModelCategoryLabel("trasporti", "treni, autobus, auto, aerei", "TRASPORTI"),
    ModelCategoryLabel("documenti e dogana", "passaporto, visto", "DOGANE"),
    ModelCategoryLabel("salute", "medici, ospedali, vaccini", "SALUTE"),
    ModelCategoryLabel("sicurezza", "criminalita', zone pericolose", "SICUREZZA"),
    ModelCategoryLabel("telefono e internet", null, "CONNETTIVITA"),
    ModelCategoryLabel("storia", "passato, fondazione, guerre", "STORIA"),
    ModelCategoryLabel("clima", "meteo, stagioni, temperature", "CLIMA"),
    ModelCategoryLabel("viaggio in generale", "jet lag, bagagli, oggetti persi", null),
)

private val modelCategoryLabelsEn = listOf(
    ModelCategoryLabel("accommodation", "hotels, sleeping, camping", "ALLOGGIO"),
    ModelCategoryLabel("food", "restaurants, dishes, drinks", "CIBO_BEVANDE"),
    ModelCategoryLabel("sightseeing", "monuments, museums, trips", "COSA_VEDERE"),
    ModelCategoryLabel("shopping", "shops, prices, money", "ACQUISTI"),
    ModelCategoryLabel("transport", "trains, buses, cars, flights", "TRASPORTI"),
    ModelCategoryLabel("documents and customs", "passport, visa", "DOGANE"),
    ModelCategoryLabel("health", "doctors, hospitals, vaccines", "SALUTE"),
    ModelCategoryLabel("safety", "crime, dangerous areas", "SICUREZZA"),
    ModelCategoryLabel("phone and internet", null, "CONNETTIVITA"),
    ModelCategoryLabel("history", "past, foundation, wars", "STORIA"),
    ModelCategoryLabel("climate", "weather, seasons, temperatures", "CLIMA"),
    ModelCategoryLabel("travel in general", "jet lag, luggage, lost items", null),
)
