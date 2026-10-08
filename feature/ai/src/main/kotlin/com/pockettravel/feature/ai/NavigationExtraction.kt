package com.pockettravel.feature.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/*
 * Pulizia della meta di una richiesta di navigazione (gia' riconosciuta da parseNavigationRequest) col modello sul
 * telefono, con l'output vincolato a NAVIGATION_GRAMMAR (OnDeviceLlmEngine.generateWithGrammar). Non e' ancora usata
 * dall'assistente: con i modelli di oggi (addestrati per domande e risposte) non migliorava le sole regole (misure in
 * NavigationGrammarDeviceTest: 32/34 con le regole e 32/34 con ognuno dei modelli da 0,8B, 2B e 4B; da solo il 4B
 * trova 31/34 mete, ma sbaglia proprio le 2 che le regole non risolvevano, "vedere il Colosseo domani" e "beach tomorrow
 * morning"; ora le regole le risolvono, 34/34). Da riprovare con esempi di addestramento per questo compito. Il mezzo
 * resta comunque alle regole: i modelli lo inventavano.
 */

// JSON a forma fissa: niente spazi liberi, cosi' il modello piccolo non puo' divagare.
internal const val NAVIGATION_GRAMMAR = """root ::= "{\"destination\": \"" dest "\"}"
dest ::= [^"\\\x00-\x1f]{1,80}
"""

// Solo la meta trovata dalle regole, non la frase intera (che i modelli ricopiavano tutta, comando compreso), e
// senza esempi (il modello da 0,8B ricopiava la destinazione dell'esempio qualunque fosse la frase).
internal fun navigationPrompt(destination: String): String = """
Testo: $destination

Il testo indica un luogo. Se contiene altre parole (verbi, orari, giorni, mezzo di trasporto), scrivi solo il nome
del luogo, copiato com'e'; altrimenti ricopia il testo.
""".trim()

/** La meta del modello, se e' una stringa non vuota; null se l'output non e' il JSON della grammatica. */
internal fun parseNavigationJson(output: String): String? = try {
    (Json.parseToJsonElement(output).jsonObject["destination"] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
} catch (_: IllegalArgumentException) {
    null
}

/**
 * Tiene la meta del modello solo se sta dentro quella delle regole: il modello puo' solo accorciarla, non inventarla,
 * tradurla o allungarla col comando ("naviga fino all'hotel Danieli").
 */
internal fun mergeNavigationRequest(rules: NavigationRequest, modelDestination: String?): NavigationRequest =
    if (modelDestination != null && rules.destination.contains(modelDestination, ignoreCase = true)) {
        rules.copy(destination = modelDestination)
    } else {
        rules
    }
