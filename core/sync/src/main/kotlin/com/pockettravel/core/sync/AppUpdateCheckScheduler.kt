package com.pockettravel.core.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class AppUpdateCheckScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager get() = WorkManager.getInstance(context)

    // Anche su dati cellulari: app-status.json e' pochi byte, non l'APK vero e proprio - il
    // download di un aggiornamento resta comunque manuale (fuori dall'app, dalla GitHub Release).
    /** Controllo periodico di app-status.json, qualunque rete — mai un download automatico. */
    fun schedulePeriodicCheck() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<AppUpdateCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(SyncConfig.APP_UPDATE_CHECK_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Controllo manuale immediato (es. tasto "Controlla aggiornamenti"), in aggiunta a quello periodico sopra. */
    fun checkNow() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<AppUpdateCheckWorker>()
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniqueWork(
            "${SyncConfig.APP_UPDATE_CHECK_WORK_NAME}-manual",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
