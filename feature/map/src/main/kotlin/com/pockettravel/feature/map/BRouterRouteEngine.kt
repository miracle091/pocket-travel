package com.pockettravel.feature.map

import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.RoutingParamCollector
import java.io.File

// BRouter e' stato verificato funzionare su Android ART reale; il suo sorgente e' vendorizzato
// in :third-party:brouter-core.
//
// API di btools.router.RoutingEngine/RoutingContext/RoutingParamCollector non documentata per uso
// embedded: ricostruita leggendo il bytecode di btools.server.BRouter.main() (il comando CLI
// standalone ufficiale), stessa metodologia javap gia' in uso nel progetto.
//
// segmentDir: cartella con uno o piu' file .rd5 (segmenti BRouter per la regione). profileDir:
// cartella con "<profileName>.brf" + lookups.dat — bundlati nell'app (asset, non dati per-regione:
// stesso profilo per tutte le regioni), vedi RouteEngineModule.
class BRouterRouteEngine(
    private val segmentDir: File,
    private val profileDir: File,
    private val profileName: String = "trekking",
) : RouteEngine {

    override fun route(from: RoutePoint, to: RoutePoint): Route? = synchronized(BROUTER_RUNTIME_LOCK) {
        // segmentBaseDir/profileBaseDir sono System property globali (stesso meccanismo usato da
        // btools.server.BRouter.main()), non parametri del costruttore — impostate ad ogni
        // chiamata perche' regionId (quindi segmentDir) puo' cambiare tra una route() e l'altra
        // sulla stessa istanza factory-creata.
        System.setProperty("segmentBaseDir", segmentDir.path)
        System.setProperty("profileBaseDir", profileDir.path)

        val lonlats = "${from.longitude},${from.latitude}|${to.longitude},${to.latitude}"
        val routingContext = RoutingContext()
        val paramCollector = RoutingParamCollector()
        val waypoints = paramCollector.getWayPointList(lonlats)
        val params = paramCollector.getUrlParams("lonlats=$lonlats&profile=$profileName")
        paramCollector.setParams(routingContext, waypoints, params)

        val engine = RoutingEngine(null, null, segmentDir, waypoints, routingContext)
        engine.doRun(60_000)

        if (engine.errorMessage != null) return null
        val track = engine.foundTrack ?: return null

        return Route(
            points = track.nodes.map { RoutePoint(ilatToLat(it.getILat()), ilonToLon(it.getILon())) },
            distanceMeters = track.distance.toDouble(),
            durationSeconds = track.getTotalSeconds().toDouble(),
        )
    }

    private companion object {
        private val BROUTER_RUNTIME_LOCK = Any()
        // Formato punto fisso di BRouter per lat/lon (verificato in RoutingParamCollector:
        // "ilon = (lon + 180) * 1_000_000", "ilat = (lat + 90) * 1_000_000") — inverso qui.
        fun ilatToLat(ilat: Int) = ilat / 1_000_000.0 - 90.0
        fun ilonToLon(ilon: Int) = ilon / 1_000_000.0 - 180.0
    }
}
