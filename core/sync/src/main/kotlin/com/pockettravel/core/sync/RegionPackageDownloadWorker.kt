package com.pockettravel.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pockettravel.core.data.PackageKind
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** Download dei pacchetti di una regione richiesti dall'utente — mai automatico. */
@HiltWorker
class RegionPackageDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val installer: RegionPackageInstaller,
    private val json: Json,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val entryJson = inputData.getString(KEY_MANIFEST_ENTRY) ?: return Result.failure()
            val kinds = inputData.getStringArray(KEY_PACKAGE_KINDS)?.map(PackageKind::valueOf)?.toSet() ?: return Result.failure()
            val entry = json.decodeFromString(RegionManifestEntry.serializer(), entryJson)
            entry.validate()
            installer.install(entry, kinds) { bytesDownloaded, totalBytes ->
                setProgress(workDataOf(KEY_BYTES_DOWNLOADED to bytesDownloaded, KEY_TOTAL_BYTES to totalBytes))
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
        const val KEY_PACKAGE_KINDS = "package_kinds"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
    }
}
