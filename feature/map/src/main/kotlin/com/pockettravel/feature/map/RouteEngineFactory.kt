package com.pockettravel.feature.map

/** Costruisce un [RouteEngine] per le regioni installate della navigazione. Una factory (non un
 *  singleton iniettato direttamente) perche' il grafo da caricare dipende dalle regioni, decise a
 *  runtime — lo stesso pattern per-regionId gia' usato da OfflineTileSource.styleJson(regionId).
 *  [regionIds] ha piu' voci quando il percorso attraversa il confine fra regioni: i loro segmenti
 *  si uniscono (vedi Rd5Merger). Sospende perche' l'unione, la prima volta, legge e scrive file. */
fun interface RouteEngineFactory {
    suspend fun create(regionIds: List<String>): RouteEngine
}
