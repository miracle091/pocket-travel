package com.pockettravel.core.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionStorage
import com.pockettravel.core.data.RegionZonePreferences
import com.pockettravel.core.data.RoutingVariantPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.io.IOException

/** Download dei pacchetti di una regione richiesti dall'utente — mai automatico. */
@HiltWorker
class RegionPackageDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val installer: RegionPackageInstaller,
    private val regionStorage: RegionStorage,
    private val regionZonePreferences: RegionZonePreferences,
    private val routingVariantPreferences: RoutingVariantPreferences,
    private val json: Json,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        var regionId: String? = null
        val result = try {
            val entryJson = inputData.getString(KEY_MANIFEST_ENTRY) ?: return Result.failure()
            val requestedKinds = inputData.getNullableStringArray(KEY_PACKAGE_KINDS)?.filterNotNull()?.map(PackageKind::valueOf)?.toSet() ?: return Result.failure()
            val manifestEntry = json.decodeFromString(RegionManifestEntry.serializer(), entryJson)
            manifestEntry.validate()
            regionId = manifestEntry.regionId
            // Qui e non da chi accoda: qualunque schermata avvii il download, mappa, percorsi e civici restano nella zona
            // scelta. Una zona senza celle dei civici toglie i civici dai pacchetti da scaricare.
            // Percorsi "solo auto" al posto di quelli completi, se scelti e offerti dal manifest.
            val carOnly = manifestEntry.regionId in routingVariantPreferences.carOnly.value && manifestEntry.routingCar != null
            val entry = manifestEntry.withRoutingVariant(carOnly).restrictedTo(regionZonePreferences.zone(manifestEntry.regionId))
            val kinds = requestedKinds.filterTo(mutableSetOf()) { it in entry.availableKinds }
            if (kinds.isEmpty()) return Result.success()
            var lastMapPercent = -1L
            // Download in primo piano (tipo dataSync): notifica con avanzamento e "Annulla", e il sistema non lo
            // ferma a schermo spento. Gli aggiornamenti dopo il primo sono asincroni e al massimo uno al secondo.
            val notification = RegionDownloadNotification(applicationContext)
            val throttle = UpdateThrottle()
            fun foregroundInfo(phase: DownloadPhase) = ForegroundInfo(
                notification.notificationId(entry.regionId),
                notification.build(id, entry.displayName, kinds, phase),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            try {
                setForeground(foregroundInfo(DownloadPhase.Files(0, 0)))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Da Android 12 un nuovo tentativo partito da sfondo non puo' avviare il servizio: si scarica comunque, senza notifica.
                Log.w(TAG, "Notifica di download non mostrata", error)
            }
            installer.install(
                entry,
                kinds,
                onProgress = { bytesDownloaded, totalBytes ->
                    setProgress(workDataOf(KEY_BYTES_DOWNLOADED to bytesDownloaded, KEY_TOTAL_BYTES to totalBytes))
                    if (throttle.tryAcquire(SystemClock.elapsedRealtime())) setForegroundAsync(foregroundInfo(DownloadPhase.Files(bytesDownloaded, totalBytes)))
                },
                onMapProgress = { bytesDone, bytesTotal ->
                    // Chiamato dal thread bloccante dell'estrazione: setProgressAsync, e solo quando cambia la percentuale.
                    val percent = if (bytesTotal > 0) bytesDone * 100 / bytesTotal else 100
                    if (percent != lastMapPercent) {
                        lastMapPercent = percent
                        setProgressAsync(workDataOf(KEY_MAP_BYTES_DONE to bytesDone, KEY_MAP_BYTES_TOTAL to bytesTotal))
                        if (throttle.tryAcquire(SystemClock.elapsedRealtime())) setForegroundAsync(foregroundInfo(DownloadPhase.ExtractingMap(bytesDone, bytesTotal)))
                    }
                },
                onInstalling = {
                    // Sempre, senza throttle: e' un cambio di fase, non un avanzamento.
                    setProgressAsync(workDataOf(KEY_INSTALLING to true))
                    setForegroundAsync(foregroundInfo(DownloadPhase.Installing))
                },
            )
            // Il Navigatore lo legge per avvisare che a piedi o in bici questi percorsi non bastano.
            if (PackageKind.ROUTING in kinds) routingVariantPreferences.setInstalledCarOnly(entry.regionId, carOnly)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: PermanentRegionPackageException) {
            Result.failure()
        } catch (_: IOException) {
            // Errore di rete: si riprova, ma non all'infinito.
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        } catch (error: Exception) {
            Log.w(TAG, "Download pacchetto regione fallito", error)
            Result.failure()
        }
        // Installazione finita (i file sono gia' stati spostati, restava una cartella vuota) o
        // fallimento definitivo: lo staging (file .part compresi) non serve piu'. Solo un nuovo
        // tentativo lo riusa. Confronto con Result.retry() (Retry.equals vale per ogni Retry): la
        // classe Result.Retry e' API riservata a WorkManager e il lint (RestrictedApi) la rifiuta.
        if (result != Result.retry()) regionId?.let(regionStorage::deleteStaging)
        return result
    }

    companion object {
        const val KEY_MANIFEST_ENTRY = "manifest_entry"
        // true dopo download ed estrazione: la riga della regione mostra "Installazione…" senza percentuale.
        const val KEY_INSTALLING = "installing"
        const val KEY_PACKAGE_KINDS = "package_kinds"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_MAP_BYTES_DONE = "map_bytes_done"
        const val KEY_MAP_BYTES_TOTAL = "map_bytes_total"
        // Tentativi ripetuti dopo il primo per errori di rete, poi il download si arrende.
        private const val MAX_RETRIES = 5
        private val TAG = RegionPackageDownloadWorker::class.java.simpleName
    }
}
