package com.pockettravel.feature.map

import kotlin.coroutines.cancellation.CancellationException

/** Costruisce un [RouteEngine] per le regioni installate della navigazione. Una factory (non un
 *  singleton iniettato direttamente) perche' il grafo da caricare dipende dalle regioni, decise a
 *  runtime — lo stesso pattern per-regionId gia' usato da OfflineTileSource.styleJson(regionId).
 *  [regionIds] ha piu' voci quando il percorso attraversa il confine fra regioni: i loro segmenti
 *  si uniscono (vedi Rd5Merger). Sospende perche' l'unione, la prima volta, legge e scrive file. */
fun interface RouteEngineFactory {
    suspend fun create(regionIds: List<String>): RouteEngine
}

/**
 * Il motore creato da [create]; se la creazione fallisce (copia dei profili, disco pieno) un motore che risponde
 * [RouteResult.Failed], cosi' chi chiede il percorso non chiude l'app.
 */
// Qualunque errore di creazione diventa un percorso non riuscito, non un crash.
@Suppress("TooGenericExceptionCaught")
internal suspend fun createOrFailed(create: suspend () -> RouteEngine): RouteEngine =
    try {
        create()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val failed = RouteResult.Failed(error.message ?: error.javaClass.simpleName)
        object : RouteEngine {
            override suspend fun route(
                from: RoutePoint,
                to: RoutePoint,
                profile: String?,
                profileParams: Map<String, String>,
                onProgress: (Double) -> Unit,
            ): RouteResult = failed
        }
    }
