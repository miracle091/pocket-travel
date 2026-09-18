package com.pockettravel.core.sync

import com.pockettravel.core.data.RegionStorage
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scarica e importa solo content.db (guida Wikivoyage + POI) di una regione, senza mappa ne'
 * segmenti di routing: usato per mostrare un'anteprima della guida al tap su una regione non
 * ancora installata (vedi RegionPreviewScreen), senza forzare il download completo del
 * pacchetto. Riusa RegionPackageDownloader/RegionContentImporter cosi' com'e' per il vero
 * download completo — nessuna duplicazione della logica di verifica SHA-256/import.
 */
class RegionGuidePreviewDownloader @Inject constructor(
    private val downloader: RegionPackageDownloader,
    private val contentImporter: RegionContentImporter,
    private val regionStorage: RegionStorage,
) {
    suspend fun preview(entry: RegionManifestEntry) = withContext(Dispatchers.IO) {
        entry.validate()
        val contentFile = entry.files.first { it.name == "content.db" }
        val dir = regionStorage.stagingDirectoryFor(entry.regionId, entry.version)
        dir.mkdirs()
        downloader.downloadAndVerify(contentFile, File(dir, "content.db"))
        contentImporter.import(entry.regionId, dir)
    }
}
