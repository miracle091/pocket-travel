package com.pockettravel.feature.ai

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
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
    private val answerKeepAlive: AnswerKeepAlive,
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
        // Il primo schermo visibile riporta l'app in primo piano: il rilascio chiesto per l'app nascosta non vale piu'.
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) onDeviceEngine.idleReleaser.onForeground()
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        // Con una risposta in corso il modello non si rilascia, nemmeno a app nascosta.
        onDeviceEngine.idleReleaser.isAnswerActive = { answerKeepAlive.active.value }
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
