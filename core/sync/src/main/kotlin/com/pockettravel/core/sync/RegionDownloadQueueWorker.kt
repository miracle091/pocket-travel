package com.pockettravel.core.sync

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pockettravel.core.data.PackageKind
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Scarica le regioni della [RegionDownloadQueue] una alla volta, con una sola notifica in primo piano. Le voci che
 * vogliono il Wi-Fi, con la sola rete mobile, restano in coda: il lavoro si riaccoda con il vincolo del Wi-Fi.
 */
@HiltWorker
class RegionDownloadQueueWorker @AssistedInject internal constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val queue: RegionDownloadQueue,
    private val download: RegionDownload,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val runner = RegionDownloadQueueRunner(queue, ::isUnmetered) { item ->
            download.run(item.entryJson, item.kinds, item.attempts, Callbacks()) {
                // Annullato dall'utente: tutta la coda («Annulla tutto») o solo questa voce, tolta dalla coda.
                cancelledByUser() || queue.state.value.current?.key != item.key
            }
        }
        return when (runner.run()) {
            QueueRunResult.FINISHED -> Result.success()
            QueueRunResult.RETRY -> Result.retry()
            QueueRunResult.WAITING_FOR_WIFI -> {
                enqueue(applicationContext, NetworkType.UNMETERED, ExistingWorkPolicy.APPEND_OR_REPLACE)
                Result.success()
            }
        }
    }

    private fun isUnmetered(): Boolean {
        val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private inner class Callbacks : DownloadCallbacks {
        private val notification = RegionDownloadNotification(applicationContext)
        private val throttle = UpdateThrottle()
        private var displayName = ""
        private var kinds = emptySet<PackageKind>()

        // Un ID fisso per tutta la coda: la notifica resta una sola e cambia voce.
        @SuppressLint("InlinedApi")
        private fun foregroundInfo(phase: DownloadPhase) = ForegroundInfo(
            QUEUE_NOTIFICATION_ID,
            notification.build(id, displayName, kinds, phase),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        override suspend fun start(displayName: String, kinds: Set<PackageKind>): Boolean {
            this.displayName = displayName
            this.kinds = kinds
            return tryForeground(foregroundInfo(DownloadPhase.Files(0, 0)), TAG, "Notifica della coda di download non mostrata")
        }

        override suspend fun onFiles(bytesDownloaded: Long, totalBytes: Long) {
            if (throttle.tryAcquire(SystemClock.elapsedRealtime())) setForegroundAsync(foregroundInfo(DownloadPhase.Files(bytesDownloaded, totalBytes)))
        }

        override fun onMap(bytesDone: Long, bytesTotal: Long) {
            if (throttle.tryAcquire(SystemClock.elapsedRealtime())) setForegroundAsync(foregroundInfo(DownloadPhase.ExtractingMap(bytesDone, bytesTotal)))
        }

        override fun onInstalling() {
            setForegroundAsync(foregroundInfo(DownloadPhase.Installing))
        }
    }

    // Il motivo dello stop si legge da Android 12: prima lo staging resta come per le altre cause.
    private fun cancelledByUser(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP

    companion object {
        const val WORK_NAME = "region-download-queue"
        private const val QUEUE_NOTIFICATION_ID = 0x0FFFF
        private val TAG = RegionDownloadQueueWorker::class.java.simpleName

        /**
         * Avvia il lavoro della coda. Da chi accoda, quando [RegionDownloadQueue.add] lo chiede, con REPLACE: al massimo
         * sostituisce un lavoro che aspetta il Wi-Fi o un nuovo tentativo. Dal worker stesso, per aspettare il Wi-Fi, con
         * APPEND_OR_REPLACE, che parte dopo di lui.
         */
        internal fun enqueue(context: Context, networkType: NetworkType, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
            val request = OneTimeWorkRequestBuilder<RegionDownloadQueueWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
        }
    }
}
