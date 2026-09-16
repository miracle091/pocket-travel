package com.pockettravel.feature.ai

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class LlmModelUpdateCheckScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager get() = WorkManager.getInstance(context)

    // Anche su dati cellulari: manifest.json e' pochi KB, non il modello IA vero e proprio
    // (~584 MB, quello si' mai su rete a consumo) — il download resta sempre esplicito e su
    // richiesta dell'utente (vedi LlmModelDownloadScheduler), qui si controlla solo se c'e' una
    // versione piu' recente.
    /** Controllo periodico del manifest, qualunque rete — mai un download automatico. */
    fun schedulePeriodicCheck() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<LlmModelUpdateCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val WORK_NAME = "ai-model-update-check"
    }
}
