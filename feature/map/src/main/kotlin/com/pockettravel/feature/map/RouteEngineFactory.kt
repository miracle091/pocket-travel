package com.pockettravel.feature.map

/** Costruisce un [RouteEngine] per una regione installata. Una factory (non un singleton
 *  iniettato direttamente) perche' il grafo da caricare dipende dalla regione, decisa a
 *  runtime dalla UI — lo stesso pattern per-regionId gia' usato da
 *  OfflineTileSource.styleJson(regionId). */
fun interface RouteEngineFactory {
    fun create(regionId: String): RouteEngine
}
