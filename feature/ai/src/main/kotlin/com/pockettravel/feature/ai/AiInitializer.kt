package com.pockettravel.feature.ai

import android.util.Log
import com.pockettravel.core.data.AppInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

class AiInitializer @Inject constructor(
    private val llmModelUpdateCheckScheduler: LlmModelUpdateCheckScheduler,
    private val llmModelManager: LlmModelManager,
    private val aiSettingsStore: AiSettingsStore,
) : AppInitializer {

    override fun onAppCreate() {
        // Fuori dal main thread: lo scheduling di WorkManager tocca il suo database, e la pulizia rimuove i
        // modelli rimasti da formati/cataloghi precedenti (.litertlm).
        // runCatching: un'eccezione non gestita in questo scope farebbe chiudere l'app all'avvio.
        // Nessuno scope applicativo iniettato in core: stesso CoroutineScope locale di SyncInitializer.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { llmModelUpdateCheckScheduler.schedulePeriodicCheck() }
                .onFailure { Log.w("AiInitializer", "Pianificazione del controllo aggiornamenti fallita", it) }
            runCatching { llmModelManager.deleteOrphanedFiles(aiSettingsStore.selectedModelDefinition()) }
                .onFailure { Log.w("AiInitializer", "Pulizia dei modelli orfani fallita", it) }
        }
    }
}
