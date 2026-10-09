package com.pockettravel.feature.map

/**
 * Percorso con i segmenti uniti di piu' regioni ([merged]); se BRouter trova un'isola attorno a partenza
 * o arrivo ("start/target island detected") si riprova con ogni regione da sola ([single], in ordine) e
 * si tiene il primo percorso trovato, altrimenti resta il risultato di [merged].
 *
 * Le regioni sono pubblicate in giorni diversi, quindi i loro segmenti vengono da build di brouter.de
 * diverse: dove l'unione passa da una build all'altra una strada puo' puntare a un nodo che nell'altra
 * build non c'e' piu', e un percorso che parte da li' finisce su un'isola. Rd5Merger sposta questi
 * bordi dove le due build combaciano; quando non puo' (nessuna micro-cella uguale nella fascia comune)
 * la regione da sola, con una build sola, ha il percorso se partenza e arrivo stanno nei suoi segmenti.
 * Solo dopo un'isola: BRouter la cerca con un numero limitato di nodi, quindi i tentativi in piu' sono
 * brevi anche quando l'isola c'e' davvero. "Nessun percorso" arriva invece dopo la ricerca completa, che
 * puo' durare minuti: ripeterla per ogni regione raddoppierebbe l'attesa.
 */
class FallbackRouteEngine(
    private val merged: RouteEngine,
    private val single: List<RouteEngine>,
) : RouteEngine {

    override suspend fun route(
        from: RoutePoint,
        to: RoutePoint,
        profile: String?,
        profileParams: Map<String, String>,
        onProgress: (Double) -> Unit,
    ): RouteResult {
        val result = merged.route(from, to, profile, profileParams, onProgress)
        if (result is RouteResult.Failed && ISLAND in result.message) {
            for (engine in single) {
                val retry = engine.route(from, to, profile, profileParams, onProgress)
                if (retry is RouteResult.Found) return retry
            }
        }
        return result
    }

    private companion object {
        // Messaggio di btools.router.RoutingEngine: "start island detected" o "target island detected for section N".
        const val ISLAND = "island detected"
    }
}
