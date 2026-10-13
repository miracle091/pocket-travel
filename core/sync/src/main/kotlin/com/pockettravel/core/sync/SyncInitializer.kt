package com.pockettravel.core.sync

import android.util.Log
import com.pockettravel.core.data.AppInitializer
import com.pockettravel.core.data.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

class SyncInitializer @Inject constructor(
    private val regionSyncScheduler: RegionSyncScheduler,
    private val appUpdateCheckScheduler: AppUpdateCheckScheduler,
    private val regionStartupRecovery: RegionStartupRecovery,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AppInitializer {

    override fun onAppCreate() {
        // Fuori dal thread principale: WorkManager apre il suo database anche solo per accodare un lavoro.
        // Scope senza annullamento: il lavoro vive quanto il processo e non esiste uno scope applicativo iniettabile.
        CoroutineScope(ioDispatcher).launch {
            regionSyncScheduler.schedulePeriodicManifestCheck()
            appUpdateCheckScheduler.schedulePeriodicCheck()
            // Attivazioni di pacchetti interrotte da un crash e staging abbandonato (vedi RegionStartupRecovery).
            // runCatching: un'eccezione non gestita in questo scope farebbe chiudere l'app all'avvio.
            runCatching { regionStartupRecovery.run() }
                .onFailure { Log.w("SyncInitializer", "Recupero dei pacchetti regionali fallito", it) }
        }
    }
}
