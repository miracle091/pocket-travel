package com.pockettravel.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Controllo periodico delle allerte delle regioni installate, con la rete (vedi [AlertNotificationsScheduler]). */
@HiltWorker
class AlertNotificationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: AlertNotificationRunner,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            runner.run()
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            Result.retry()
        }
    }
}
