package com.pockettravel.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.pockettravel.core.sync.AppUpdateCheckScheduler
import com.pockettravel.core.sync.RegionStartupRecovery
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
    @Inject lateinit var regionStartupRecovery: RegionStartupRecovery

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        regionSyncScheduler.schedulePeriodicManifestCheck()
        appUpdateCheckScheduler.schedulePeriodicCheck()
        llmModelUpdateCheckScheduler.schedulePeriodicCheck()
        // Fuori dal main thread: rimuove i modelli rimasti da formati/cataloghi precedenti (.litertlm).
        // runCatching: un'eccezione non gestita in questo scope farebbe chiudere l'app all'avvio.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { llmModelManager.deleteOrphanedFiles() }
                .onFailure { Log.w("PocketTravelApp", "Pulizia dei modelli orfani fallita", it) }
        }
        // Attivazioni di pacchetti interrotte da un crash e staging abbandonato (vedi RegionStartupRecovery).
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { regionStartupRecovery.run() }
                .onFailure { Log.w("PocketTravelApp", "Recupero dei pacchetti regionali fallito", it) }
        }
    }
}
