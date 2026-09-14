package com.pockettravel.feature.ai

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Rende il download del modello persistente a un kill di processo dell'app — stesso pattern
 * di RegionSyncScheduler applicato ai pacchetti regionali: un CoroutineWorker con retry via
 * WorkManager invece di viewModelScope.launch.
 */
class LlmModelDownloadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager get() = WorkManager.getInstance(context)

    fun enqueueDownload() {
        val request = OneTimeWorkRequestBuilder<LlmModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun observeDownload(): Flow<WorkInfo?> =
        workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).map { it.firstOrNull() }

    fun cancelDownload() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    private companion object {
        const val WORK_NAME = "llm_model_download"
    }
}
