package com.pockettravel.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Sposta i "segmenti della rete stradale" (.rd5) scaricati (uno o piu' segmenti BRouter, un file per ogni tile 5x5
 * attraversata dalla regione — vedi tools/data-pipeline/routing) da packageDir/
 * (dove RegionPackageDownloader li scrive, come poi.db/map.pmtiles) a
 * packageDir/routing/ — la cartella che BRouterRouteEngine si aspetta (RouteEngineModule,
 * feature/map). Un .rd5 e' gia' pronto all'uso: basta spostarlo nella cartella giusta, nessuna
 * estrazione.
 */
class RoutingSegmentsInstaller @Inject constructor() {

    /**
     * [names]: i segmenti della richiesta. Lo staging puo' contenere .rd5 completati da un tentativo precedente
     * (un'altra zona): solo questi si installano. Null = tutti.
     */
    suspend fun install(packageDir: File, names: Set<String>? = null) = withContext(Dispatchers.IO) {
        val routingDir = File(packageDir, ROUTING_DIR_NAME)
        routingDir.deleteRecursively()
        routingDir.mkdirs()

        val segments = packageDir.listFiles { file -> file.isFile && file.extension == "rd5" && (names == null || file.name in names) }.orEmpty()
        check(segments.isNotEmpty()) { "Il pacchetto non contiene segmenti routing" }
        segments.forEach { rd5File ->
                check(rd5File.renameTo(File(routingDir, rd5File.name))) {
                    "Impossibile installare il segmento ${rd5File.name}"
                }
        }
    }

    companion object {
        // Deve combaciare con RouteEngineModule.ROUTING_DIR_NAME (feature/map).
        const val ROUTING_DIR_NAME = "routing"
    }
}
