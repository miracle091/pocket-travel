package com.pockettravel.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.pockettravel.core.sync.AppUpdateCheckScheduler
import com.pockettravel.core.sync.RegionSyncScheduler
import com.pockettravel.feature.ai.LlmModelManager
import com.pockettravel.feature.ai.LlmModelUpdateCheckScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@HiltAndroidApp
class PocketTravelApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var regionSyncScheduler: RegionSyncScheduler
    @Inject lateinit var appUpdateCheckScheduler: AppUpdateCheckScheduler
    @Inject lateinit var llmModelUpdateCheckScheduler: LlmModelUpdateCheckScheduler
    @Inject lateinit var llmModelManager: LlmModelManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        regionSyncScheduler.schedulePeriodicManifestCheck()
        appUpdateCheckScheduler.schedulePeriodicCheck()
        llmModelUpdateCheckScheduler.schedulePeriodicCheck()
        // Fuori dal main thread: rimuove i modelli rimasti da formati/cataloghi precedenti (.litertlm).
        CoroutineScope(Dispatchers.IO).launch { llmModelManager.deleteOrphanedFiles() }
    }
}
