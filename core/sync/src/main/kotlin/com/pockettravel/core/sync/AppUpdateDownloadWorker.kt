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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Download dell'APK di un aggiornamento dell'app richiesto dall'utente, mai automatico. */
@HiltWorker
class AppUpdateDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val fetcher: AppUpdateFetcher,
    private val storage: AppUpdateStorage,
) : CoroutineWorker(context, params) {

    @Suppress("TooGenericExceptionCaught") // Qualunque errore del servizio in primo piano: si scarica senza notifica.
    override suspend fun doWork(): Result {
        val entry = entryFromInput() ?: return Result.failure(failureData(AppUpdateFailure.INVALID_FILE))
        val totalBytes = entry.apkSizeBytes ?: 0L
        return try {
            val notification = AppUpdateDownloadNotification(applicationContext)
            val throttle = UpdateThrottle()

            // Il tipo del servizio esiste da Android 10: WorkManager lo ignora sulle versioni precedenti.
            @SuppressLint("InlinedApi")
            fun foregroundInfo(bytesDownloaded: Long) = ForegroundInfo(
                downloadNotificationId(id),
                notification.build(id, bytesDownloaded, totalBytes),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            // Da Android 12 un nuovo tentativo partito da sfondo non può avviare il servizio: si scarica senza notifica.
            try {
                setForeground(foregroundInfo(0))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Notifica di download non mostrata", error)
            }
            val failure = fetcher.fetch(entry) { bytesDownloaded ->
                setProgress(workDataOf(KEY_BYTES_DOWNLOADED to bytesDownloaded, KEY_TOTAL_BYTES to totalBytes))
                if (throttle.tryAcquire(SystemClock.elapsedRealtime())) setForegroundAsync(foregroundInfo(bytesDownloaded))
            }
            if (failure == null) Result.success(workDataOf(KEY_VERSION_CODE to entry.versionCode)) else Result.failure(failureData(failure))
        } catch (error: CancellationException) {
            // "Annulla" della notifica: il download non riparte. Altre cause (vincoli persi) lasciano il .part per la ripresa.
            if (cancelledByUser()) storage.delete(entry.versionCode)
            throw error
        } catch (_: PermanentRegionPackageException) {
            storage.delete(entry.versionCode)
            Result.failure(failureData(AppUpdateFailure.INVALID_FILE))
        } catch (_: IOException) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure(failureData(AppUpdateFailure.NETWORK))
        }
    }

    private fun entryFromInput(): AppVersionEntry? {
        val entry = AppVersionEntry(
            versionName = inputData.getString(KEY_VERSION_NAME).orEmpty(),
            versionCode = inputData.getInt(KEY_VERSION_CODE, 0),
            apkSha256 = inputData.getString(KEY_SHA256),
            apkSizeBytes = inputData.getLong(KEY_TOTAL_BYTES, 0L),
        )
        return runCatching { entry.validate() }.map { entry }.getOrNull()
    }

    private fun failureData(failure: AppUpdateFailure) = workDataOf(KEY_FAILURE to failure.name)

    // Il motivo dello stop si legge da Android 12.
    private fun cancelledByUser(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP

    companion object {
        const val KEY_VERSION_NAME = "version_name"
        const val KEY_VERSION_CODE = "version_code"
        const val KEY_SHA256 = "sha256"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_FAILURE = "failure"

        // Come i download delle nazioni: tentativi dopo il primo per errori di rete, poi ci si arrende.
        private const val MAX_RETRIES = 5
        private val TAG = AppUpdateDownloadWorker::class.java.simpleName
    }
}
