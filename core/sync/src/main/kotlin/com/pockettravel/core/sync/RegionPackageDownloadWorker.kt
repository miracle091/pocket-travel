package com.pockettravel.core.sync

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pockettravel.core.data.PackageKind
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.io.File

/** Download dei pacchetti di una regione richiesti dall'utente — mai automatico. */
@HiltWorker
class RegionPackageDownloadWorker @AssistedInject internal constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val download: RegionPackageDownload,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val requestFile = inputData.getString(KEY_MANIFEST_ENTRY_FILE)
        val entryJson = entryJson(requestFile)
        val kinds = inputData.getNullableStringArray(KEY_PACKAGE_KINDS)?.filterNotNull()?.map(PackageKind::valueOf)?.toSet()
        if (entryJson == null || kinds == null) return finish(Result.failure(), requestFile)
        val outcome = try {
            download.run(entryJson, kinds, runAttemptCount, Callbacks(), ::cancelledByUser)
        } catch (error: CancellationException) {
            // "Annulla" della notifica: anche il file della richiesta non serve piu'.
            if (cancelledByUser()) deleteRequest(requestFile)
            throw error
        } catch (error: OutOfMemoryError) {
            deleteRequest(requestFile)
            throw error
        }
        // Solo un nuovo tentativo rilegge il file della richiesta.
        if (outcome != DownloadOutcome.RETRY) deleteRequest(requestFile)
        return when (outcome) {
            DownloadOutcome.SUCCESS -> Result.success()
            DownloadOutcome.RETRY -> Result.retry()
            DownloadOutcome.FAILURE -> Result.failure()
        }
    }

    // Download in primo piano (tipo dataSync): notifica con avanzamento e "Annulla", e il sistema non lo
    // ferma a schermo spento. Gli aggiornamenti dopo il primo sono asincroni e al massimo uno al secondo.
    private inner class Callbacks : DownloadCallbacks {
        private val notification = RegionDownloadNotification(applicationContext)
        private val throttle = UpdateThrottle()
        private var displayName = ""
        private var kinds = emptySet<PackageKind>()

        // Il tipo del servizio esiste da Android 10: WorkManager lo ignora sulle versioni precedenti.
        @SuppressLint("InlinedApi")
        private fun foregroundInfo(phase: DownloadPhase) = ForegroundInfo(
            downloadNotificationId(id),
            notification.build(id, displayName, kinds, phase),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        override suspend fun start(displayName: String, kinds: Set<PackageKind>): Boolean {
            this.displayName = displayName
            this.kinds = kinds
            // Da Android 12 il servizio in primo piano non parte da sfondo (ForegroundServiceStartNotAllowedException):
            // si scarica comunque, senza notifica.
            @Suppress("TooGenericExceptionCaught")
            val shown = try {
                setForeground(foregroundInfo(DownloadPhase.Files(0, 0)))
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Notifica di download non mostrata", error)
                false
            }
            return shown
        }

        override suspend fun onFiles(bytesDownloaded: Long, totalBytes: Long) {
            setProgress(workDataOf(KEY_BYTES_DOWNLOADED to bytesDownloaded, KEY_TOTAL_BYTES to totalBytes))
            if (throttle.tryAcquire(SystemClock.elapsedRealtime())) setForegroundAsync(foregroundInfo(DownloadPhase.Files(bytesDownloaded, totalBytes)))
        }

        override fun onMap(bytesDone: Long, bytesTotal: Long) {
            setProgressAsync(workDataOf(KEY_MAP_BYTES_DONE to bytesDone, KEY_MAP_BYTES_TOTAL to bytesTotal))
            if (throttle.tryAcquire(SystemClock.elapsedRealtime())) setForegroundAsync(foregroundInfo(DownloadPhase.ExtractingMap(bytesDone, bytesTotal)))
        }

        override fun onInstalling() {
            // Sempre, senza throttle: e' un cambio di fase, non un avanzamento.
            setProgressAsync(workDataOf(KEY_INSTALLING to true))
            setForegroundAsync(foregroundInfo(DownloadPhase.Installing))
        }
    }

    // Uscite anticipate di doWork: anche loro tolgono il file della richiesta.
    private fun finish(result: Result, requestFile: String?): Result {
        deleteRequest(requestFile)
        return result
    }

    // Il motivo dello stop si legge da Android 12: prima lo staging resta come per le altre cause.
    private fun cancelledByUser(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP

    private fun deleteRequest(requestFile: String?) {
        requestFile?.let { requestFiles(applicationContext).delete(it) }
    }

    // KEY_MANIFEST_ENTRY: lavori accodati prima che la voce passasse da un file, ancora in coda dopo l'aggiornamento.
    private fun entryJson(requestFile: String?): String? =
        requestFile?.let { requestFiles(applicationContext).read(it) } ?: inputData.getString(KEY_MANIFEST_ENTRY)

    companion object {
        const val KEY_MANIFEST_ENTRY = "manifest_entry"
        const val KEY_MANIFEST_ENTRY_FILE = "manifest_entry_file"
        // true dopo download ed estrazione: la riga della regione mostra "Installazione…" senza percentuale.
        const val KEY_INSTALLING = "installing"
        const val KEY_PACKAGE_KINDS = "package_kinds"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_MAP_BYTES_DONE = "map_bytes_done"
        const val KEY_MAP_BYTES_TOTAL = "map_bytes_total"
        private val TAG = RegionPackageDownloadWorker::class.java.simpleName

        internal fun requestFiles(context: Context) = DownloadRequestFiles(File(context.noBackupFilesDir, "download-requests"))
    }
}
