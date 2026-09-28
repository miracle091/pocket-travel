package com.pockettravel.core.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Controllo periodico (qualunque rete incluse i dati cellulari, vedi [AppUpdateCheckScheduler]):
 * legge app-status.json (asset della release GitHub "app-status", pubblicato da publish-apk.yml
 * ad ogni rilascio app — non legato all'aggiornamento dei pacchetti regionali) e, se
 * versionCode e' cambiato, notifica — non scarica mai automaticamente.
 */
@HiltWorker
class AppUpdateCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val appStatusClient: AppStatusClient,
    private val notifier: UpdateAvailableNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val appVersion = appStatusClient.fetchAppStatus().appVersion
            if (appVersion != null && isAppUpdateAvailable(installedAppVersionCode(applicationContext), appVersion)) {
                notifier.notifyAppUpdateAvailable(appVersion)
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            Result.retry()
        } catch (error: Exception) {
            Log.w(TAG, "Controllo aggiornamento app fallito", error)
            Result.failure()
        }
    }

    private companion object {
        private val TAG = AppUpdateCheckWorker::class.java.simpleName
    }
}

internal fun isAppUpdateAvailable(installedVersionCode: Long, remote: AppVersionEntry): Boolean =
    remote.versionCode > installedVersionCode
