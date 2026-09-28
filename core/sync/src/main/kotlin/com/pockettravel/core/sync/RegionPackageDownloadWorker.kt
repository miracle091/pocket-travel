package com.pockettravel.core.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionStorage
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
    private val json: Json,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        var regionId: String? = null
        val result = try {
            val entryJson = inputData.getString(KEY_MANIFEST_ENTRY) ?: return Result.failure()
            val kinds = inputData.getNullableStringArray(KEY_PACKAGE_KINDS)?.filterNotNull()?.map(PackageKind::valueOf)?.toSet() ?: return Result.failure()
            val entry = json.decodeFromString(RegionManifestEntry.serializer(), entryJson)
            entry.validate()
            regionId = entry.regionId
            installer.install(entry, kinds) { bytesDownloaded, totalBytes ->
                setProgress(workDataOf(KEY_BYTES_DOWNLOADED to bytesDownloaded, KEY_TOTAL_BYTES to totalBytes))
            }
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
        const val KEY_PACKAGE_KINDS = "package_kinds"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        // Tentativi ripetuti dopo il primo per errori di rete, poi il download si arrende.
        private const val MAX_RETRIES = 5
        private val TAG = RegionPackageDownloadWorker::class.java.simpleName
    }
}
