package com.pockettravel.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionPackage
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.RegionStorage
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** Download di un pacchetto regionale richiesto dall'utente — mai automatico. */
@HiltWorker
class RegionPackageDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val downloader: RegionPackageDownloader,
    private val regionRepository: RegionRepository,
    private val regionStorage: RegionStorage,
    private val poiImporter: PoiImporter,
    private val routingGraphInstaller: RegionRoutingGraphInstaller,
    private val pmtilesExtractor: PmtilesExtractor,
    private val json: Json,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val entryJson = inputData.getString(KEY_MANIFEST_ENTRY) ?: return Result.failure()
            val entry = json.decodeFromString(RegionManifestEntry.serializer(), entryJson)
            entry.validate()
            val stagingVersion = PackageKind.entries.joinToString("_") { entry.versionOf(it) }
            val stagingDir = downloader.download(entry.regionId, stagingVersion, entry.routing.files + entry.poi.file) { bytesDownloaded, totalBytes ->
                setProgress(workDataOf(KEY_BYTES_DOWNLOADED to bytesDownloaded, KEY_TOTAL_BYTES to totalBytes))
            }
            // map.pmtiles non e' tra i file scaricati (vedi MapPackageEntry):
            // assemblato qui estraendo solo le tile del bounding box dalla build Protomaps.
            pmtilesExtractor.extract(entry.map.source, File(stagingDir, "map.pmtiles"))
            routingGraphInstaller.install(stagingDir)
            val activation = regionStorage.activate(entry.regionId, stagingDir)
            try {
                regionRepository.inInstallTransaction {
                    val packageDir = regionStorage.directoryFor(entry.regionId)
                    poiImporter.import(entry.regionId, File(packageDir, entry.poi.file.name))
                    regionRepository.markInstalled(
                        RegionPackage(
                            entry.regionId, entry.displayName,
                            mapVersion = entry.map.version, routingVersion = entry.routing.version, poiVersion = entry.poi.version,
                            sizeBytes = entry.downloadBytes(PackageKind.entries.toSet()),
                        ),
                    )
                }
                activation.commit()
                regionStorage.cleanupStagingExcept(entry.regionId, stagingVersion)
            } catch (error: Exception) {
                activation.rollback()
                throw error
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: PermanentRegionPackageException) {
            Result.failure()
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Exception) {
            Result.failure()
        }
    }

    companion object {
        const val KEY_MANIFEST_ENTRY = "manifest_entry"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
    }
}
