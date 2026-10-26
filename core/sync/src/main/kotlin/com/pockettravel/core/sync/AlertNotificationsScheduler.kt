package com.pockettravel.core.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Accende o spegne il controllo periodico delle allerte. */
fun interface AlertNotificationsScheduler {
    fun setScheduled(enabled: Boolean)
}

class WorkManagerAlertNotificationsScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : AlertNotificationsScheduler {

    override fun setScheduled(enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(SyncConfig.ALERT_NOTIFICATIONS_WORK_NAME)
            return
        }
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<AlertNotificationWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(SyncConfig.ALERT_NOTIFICATIONS_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
