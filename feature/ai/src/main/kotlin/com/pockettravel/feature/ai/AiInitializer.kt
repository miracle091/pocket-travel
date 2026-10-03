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
) : AppInitializer {

    override fun onAppCreate() {
        llmModelUpdateCheckScheduler.schedulePeriodicCheck()
        // Fuori dal main thread: rimuove i modelli rimasti da formati/cataloghi precedenti (.litertlm).
        // runCatching: un'eccezione non gestita in questo scope farebbe chiudere l'app all'avvio.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { llmModelManager.deleteOrphanedFiles() }
                .onFailure { Log.w("AiInitializer", "Pulizia dei modelli orfani fallita", it) }
        }
    }
}
