package com.pockettravel.core.sync

import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sposta i file .rd5 scaricati (uno o piu' segmenti BRouter, un file per ogni tile 5x5
 * attraversata dalla regione — vedi tools/data-pipeline/routing) da packageDir/
 * (dove RegionPackageDownloader li scrive, come content.db/map.pmtiles) a
 * packageDir/routing/ — la cartella che BRouterRouteEngine si aspetta (RouteEngineModule,
 * feature/map). A differenza del vecchio routing.ghz di GraphHopper (uno zip da estrarre), un
 * .rd5 e' gia' pronto all'uso: basta spostarlo nella cartella giusta, nessuna estrazione.
 */
class RegionRoutingGraphInstaller @Inject constructor() {

    suspend fun install(packageDir: File) = withContext(Dispatchers.IO) {
        val routingDir = File(packageDir, ROUTING_DIR_NAME)
        routingDir.deleteRecursively()
        routingDir.mkdirs()

        val segments = packageDir.listFiles { file -> file.isFile && file.extension == "rd5" }.orEmpty()
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
