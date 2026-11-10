package com.pockettravel.core.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Stato del download dell'aggiornamento dell'app. */
sealed interface AppUpdateState {
    data object Idle : AppUpdateState

    /** [totalBytes] è 0 finché il download non è partito; [waitingFor] non nullo se manca la rete che il download chiede. */
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long, val waitingFor: AppUpdateWait? = null) : AppUpdateState

    /** [file] è l'APK scaricato e controllato (impronta, pacchetto, versione e firma). */
    data class Ready(val versionCode: Int, val file: File) : AppUpdateState
    /** [versionCode]: la versione del download fallito, 0 se il lavoro non l'ha scritta. */
    data class Failed(val reason: AppUpdateFailure, val versionCode: Int) : AppUpdateState
}

/** La rete che manca a un download fermo: una qualunque, o una senza limiti di traffico se l'utente non ha accettato la rete a consumo. */
enum class AppUpdateWait { NETWORK, WIFI }

/** La rete attiva: [connected] con internet raggiungibile, [unmetered] senza limiti di traffico. */
internal data class ActiveNetwork(val connected: Boolean, val unmetered: Boolean)

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

    private val connectivityManager get() = context.getSystemService(ConnectivityManager::class.java)

    override val state: Flow<AppUpdateState>
        get() = combine(workManager.getWorkInfosForUniqueWorkFlow(SyncConfig.APP_UPDATE_DOWNLOAD_WORK_NAME), activeNetwork()) { infos, network ->
            val info = infos.firstOrNull()
            appUpdateState(
                info?.state,
                info?.outputData ?: Data.EMPTY,
                info?.progress ?: Data.EMPTY,
                storage,
                info?.tags.orEmpty(),
                networkWait(info?.constraints?.requiredNetworkType, network),
            )
        }

    override fun isNetworkMetered(): Boolean = connectivityManager.isActiveNetworkMetered

    private fun activeNetwork(): Flow<ActiveNetwork> = callbackFlow {
        fun send(capabilities: NetworkCapabilities?) {
            trySend(
                ActiveNetwork(
                    connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                    unmetered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
                ),
            )
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = send(capabilities)
            override fun onLost(network: Network) = send(null)
        }
        send(connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork))
        connectivityManager.registerDefaultNetworkCallback(callback)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    override suspend fun start(entry: AppVersionEntry, allowMetered: Boolean): Boolean {
        val hasApk = entry.apkSha256 != null && entry.apkSizeBytes != null
        if (hasApk) {
            val data = Data.Builder()
                .putString(AppUpdateDownloadWorker.KEY_VERSION_NAME, entry.versionName)
                .putInt(AppUpdateDownloadWorker.KEY_VERSION_CODE, entry.versionCode)
                .putString(AppUpdateDownloadWorker.KEY_SHA256, entry.apkSha256)
                .putLong(AppUpdateDownloadWorker.KEY_TOTAL_BYTES, entry.apkSizeBytes)
                .build()
            val network = if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
            val request = OneTimeWorkRequestBuilder<AppUpdateDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .setInputData(data)
                .addTag(TAG_VERSION_CODE + entry.versionCode)
                .addTag(TAG_TOTAL_BYTES + (entry.apkSizeBytes))
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

// Versione e peso dell'APK nei tag del lavoro: WorkManager non espone i dati d'ingresso.
internal const val TAG_VERSION_CODE = "app-update-version-code:"
internal const val TAG_TOTAL_BYTES = "app-update-total-bytes:"

private fun Set<String>.tagValue(prefix: String): Long? = firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.toLongOrNull()

/** La rete che manca al download, o null se quella attiva va bene ([required] è il vincolo del lavoro). */
internal fun networkWait(required: NetworkType?, network: ActiveNetwork): AppUpdateWait? = when {
    required == NetworkType.UNMETERED && !(network.connected && network.unmetered) -> AppUpdateWait.WIFI
    required != null && required != NetworkType.NOT_REQUIRED && !network.connected -> AppUpdateWait.NETWORK
    else -> null
}

@Suppress("LongParameterList") // Lo stato si compone da tutte le parti del WorkInfo e dalla rete.
internal fun appUpdateState(
    workState: WorkInfo.State?,
    output: Data,
    progress: Data,
    storage: AppUpdateStorage,
    tags: Set<String> = emptySet(),
    waitingFor: AppUpdateWait? = null,
): AppUpdateState =
    when (workState) {
        null, WorkInfo.State.CANCELLED -> AppUpdateState.Idle
        WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
            // L'avanzamento c'è solo mentre il lavoro gira: fermo per la rete, si legge dal file parziale.
            val partBytes = tags.tagValue(TAG_VERSION_CODE)?.let { storage.partFile(it.toInt()).length() } ?: 0L
            AppUpdateState.Downloading(
                progress.getLong(AppUpdateDownloadWorker.KEY_BYTES_DOWNLOADED, partBytes),
                progress.getLong(AppUpdateDownloadWorker.KEY_TOTAL_BYTES, tags.tagValue(TAG_TOTAL_BYTES) ?: 0L),
                waitingFor,
            )
        }
        WorkInfo.State.SUCCEEDED -> {
            val versionCode = output.getInt(AppUpdateDownloadWorker.KEY_VERSION_CODE, 0)
            val file = storage.apkFile(versionCode)
            // L'APK può essere stato tolto dalla pulizia all'avvio dopo l'installazione.
            if (file.isFile) AppUpdateState.Ready(versionCode, file) else AppUpdateState.Idle
        }
        WorkInfo.State.FAILED -> AppUpdateState.Failed(
            AppUpdateFailure.entries.firstOrNull { it.name == output.getString(AppUpdateDownloadWorker.KEY_FAILURE) }
                ?: AppUpdateFailure.NETWORK,
            output.getInt(AppUpdateDownloadWorker.KEY_VERSION_CODE, 0),
        )
    }
