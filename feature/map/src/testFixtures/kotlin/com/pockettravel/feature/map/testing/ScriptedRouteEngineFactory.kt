package com.pockettravel.feature.map.testing

import com.pockettravel.feature.map.RouteEngine
import com.pockettravel.feature.map.RouteEngineFactory
import com.pockettravel.feature.map.RoutePoint
import com.pockettravel.feature.map.RouteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * [RouteEngineFactory] con risposte decise dal test: [results] in ordine (l'ultima si ripete), poi [defaultResult].
 * Registra le regioni di ogni motore creato e le richieste di percorso. Con [gate] il calcolo aspetta lo sblocco.
 */
class ScriptedRouteEngineFactory(var defaultResult: RouteResult = RouteResult.NotFound) : RouteEngineFactory {
    class Request(val from: RoutePoint, val to: RoutePoint, val profile: String?, val params: Map<String, String>)

    val results = ArrayDeque<RouteResult>()
    val created = mutableListOf<List<String>>()
    val requests = mutableListOf<Request>()
    var gate: CompletableDeferred<Unit>? = null

    /** Avanzamenti che il motore segnala prima di rispondere. */
    var progressSteps: List<Double> = emptyList()

    /** Un avanzamento segnalato dopo l'annullamento del calcolo, quando [CompletableDeferred] ha il valore (come il motore vero). */
    var staleProgress: CompletableDeferred<Double>? = null

    override suspend fun create(regionIds: List<String>): RouteEngine {
        created += regionIds
        return object : RouteEngine {
            override suspend fun route(
                from: RoutePoint, to: RoutePoint, profile: String?, profileParams: Map<String, String>, onProgress: (Double) -> Unit,
            ): RouteResult {
                requests += Request(from, to, profile, profileParams)
                progressSteps.forEach(onProgress)
                try {
                    gate?.await()
                } finally {
                    staleProgress?.let { late -> withContext(NonCancellable) { onProgress(late.await()) } }
                }
                return if (results.size > 1) results.removeFirst() else results.firstOrNull() ?: defaultResult
            }
        }
    }
}
