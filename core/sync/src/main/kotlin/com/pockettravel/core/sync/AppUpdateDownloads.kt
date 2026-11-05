package com.pockettravel.core.sync

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Stato del download dell'aggiornamento dell'app. */
sealed interface AppUpdateState {
    data object Idle : AppUpdateState

    /** [totalBytes] è 0 finché il download non è partito. */
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : AppUpdateState

    /** [file] è l'APK scaricato e controllato (impronta, pacchetto, versione e firma). */
    data class Ready(val versionCode: Int, val file: File) : AppUpdateState
    data class Failed(val reason: AppUpdateFailure) : AppUpdateState
}

/** Il download dell'APK di un aggiornamento: parte solo da una chiamata esplicita, mai da solo. */
interface AppUpdateDownloads {
    val state: Flow<AppUpdateState>

    /** Vero se la rete attiva è a consumo: prima di [start] l'utente deve confermare. */
    fun isNetworkMetered(): Boolean

    /**
     * Avvia il download di [entry]; falso se [entry] non ha impronta e dimensione dell'APK (resta il link a GitHub).
     * Con [allowMetered] falso il lavoro aspetta una rete non a consumo.
     */
    suspend fun start(entry: AppVersionEntry, allowMetered: Boolean): Boolean
    fun cancel()
}

/** [AppUpdateDownloads] sul lavoro unico `app-update-download` di WorkManager. */
class WorkManagerAppUpdateDownloads @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: AppUpdateStorage,
) : AppUpdateDownloads {
    private val workManager get() = WorkManager.getInstance(context)

    override val state: Flow<AppUpdateState>
        get() = workManager.getWorkInfosForUniqueWorkFlow(SyncConfig.APP_UPDATE_DOWNLOAD_WORK_NAME).map { infos ->
            val info = infos.firstOrNull()
            appUpdateState(info?.state, info?.outputData ?: Data.EMPTY, info?.progress ?: Data.EMPTY, storage)
        }

    override fun isNetworkMetered(): Boolean =
        context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered

    override suspend fun start(entry: AppVersionEntry, allowMetered: Boolean): Boolean {
        val hasApk = entry.apkSha256 != null && entry.apkSizeBytes != null
        if (hasApk) {
            val data = Data.Builder()
                .putString(AppUpdateDownloadWorker.KEY_VERSION_NAME, entry.versionName)
                .putInt(AppUpdateDownloadWorker.KEY_VERSION_CODE, entry.versionCode)
                .putString(AppUpdateDownloadWorker.KEY_SHA256, entry.apkSha256)
                .putLong(AppUpdateDownloadWorker.KEY_TOTAL_BYTES, entry.apkSizeBytes ?: 0L)
                .build()
            val network = if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
            val request = OneTimeWorkRequestBuilder<AppUpdateDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .setInputData(data)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork(SyncConfig.APP_UPDATE_DOWNLOAD_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
        return hasApk
    }

    override fun cancel() {
        workManager.cancelUniqueWork(SyncConfig.APP_UPDATE_DOWNLOAD_WORK_NAME)
    }
}

internal fun appUpdateState(workState: WorkInfo.State?, output: Data, progress: Data, storage: AppUpdateStorage): AppUpdateState =
    when (workState) {
        null, WorkInfo.State.CANCELLED -> AppUpdateState.Idle
        WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> AppUpdateState.Downloading(
            progress.getLong(AppUpdateDownloadWorker.KEY_BYTES_DOWNLOADED, 0L),
            progress.getLong(AppUpdateDownloadWorker.KEY_TOTAL_BYTES, 0L),
        )
        WorkInfo.State.SUCCEEDED -> {
            val versionCode = output.getInt(AppUpdateDownloadWorker.KEY_VERSION_CODE, 0)
            val file = storage.apkFile(versionCode)
            // L'APK può essere stato tolto dalla pulizia all'avvio dopo l'installazione.
            if (file.isFile) AppUpdateState.Ready(versionCode, file) else AppUpdateState.Idle
        }
        WorkInfo.State.FAILED -> AppUpdateState.Failed(
            AppUpdateFailure.entries.firstOrNull { it.name == output.getString(AppUpdateDownloadWorker.KEY_FAILURE) }
                ?: AppUpdateFailure.NETWORK,
        )
    }
