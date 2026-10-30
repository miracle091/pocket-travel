package com.pockettravel.feature.ai

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.pockettravel.core.data.AppInitializer
import com.pockettravel.core.data.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

class AiInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val onDeviceEngine: LlamaOnDeviceLlmEngine,
    private val llmModelUpdateCheckScheduler: LlmModelUpdateCheckScheduler,
    private val llmModelManager: LlmModelManager,
    private val aiSettingsStore: AiSettingsStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AppInitializer {

    override fun onAppCreate() {
        // Il modello si toglie dalla memoria con l'app in background o con la memoria scarsa (ModelIdleReleaser).
        context.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = onDeviceEngine.idleReleaser.onTrimMemory(level)
            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        })
        // Fuori dal main thread: lo scheduling di WorkManager tocca il suo database, e la pulizia rimuove i
        // modelli rimasti da formati/cataloghi precedenti (.litertlm).
        // runCatching: un'eccezione non gestita in questo scope farebbe chiudere l'app all'avvio.
        // Nessuno scope applicativo iniettato in core: stesso CoroutineScope locale di SyncInitializer.
        CoroutineScope(ioDispatcher).launch {
            runCatching { llmModelUpdateCheckScheduler.schedulePeriodicCheck() }
                .onFailure { Log.w("AiInitializer", "Pianificazione del controllo aggiornamenti fallita", it) }
            runCatching { llmModelManager.deleteOrphanedFiles(aiSettingsStore.selectedModelDefinition()) }
                .onFailure { Log.w("AiInitializer", "Pulizia dei modelli orfani fallita", it) }
        }
    }
}
