package com.pockettravel.feature.ai

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.feature.ai.llamacpp.CpuBackendInfo
import com.pockettravel.feature.ai.llamacpp.CpuBackendOverride
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val SAMPLE_INTERVAL_MS = 1_000L
private const val BYTES_PER_KB = 1024L

// Letture tenute per l'esportazione: 30 minuti a una lettura al secondo.
private const val MAX_SAMPLES = 1_800
private const val LOGCAT_LINES = 5_000

/** Un modello scaricato: nome del file e dimensione su disco. */
data class ModelDiskUsage(val fileName: String, val sizeBytes: Long)

/** Una lettura del task manager: spazio, risorse del processo e comportamento del motore. */
data class LlmTaskSnapshot(
    val models: List<ModelDiskUsage>,
    val freeStorageBytes: Long,
    val totalPssBytes: Long,
    /** Null quando il sistema non la attribuisce (Android 17 la riporta sempre a 0). */
    val nativePssBytes: Long?,
    val nativeHeapBytes: Long,
    val systemTotalRamBytes: Long,
    val systemAvailableRamBytes: Long,
    val lowMemory: Boolean,
    val cpuPercent: Float,
    val cpuCores: Int,
    val threadCount: Int?,
    val runtime: LlmRuntimeStats,
    /** Variante CPU di llama.cpp ed estensioni della CPU; null finche' la libreria nativa non e' inizializzata. */
    val cpuBackend: CpuBackendInfo? = null,
)

/** Una lettura del task manager con l'ora (epoch ms) in cui e' stata presa. */
data class TimedSnapshot(val atMs: Long, val snapshot: LlmTaskSnapshot)

/**
 * Misure per il task manager dei modelli IA (solo build di debug): campiona ogni secondo disco, memoria e
 * CPU del processo e le affianca a [OnDeviceLlmEngine.stats]. Il processo e' unico: la memoria dei pesi
 * (mappati da file) compare nel PSS, non nell'heap nativo. Dalla prima apertura del task manager le letture
 * continuano anche a dialogo chiuso (durante le domande all'assistente), fino alla chiusura dell'app, e si
 * possono esportare con il logcat del processo.
 */
@Singleton
class LlmTaskMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: LlmModelManager,
    private val engine: OnDeviceLlmEngine,
) {
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val samples = ArrayDeque<TimedSnapshot>()

    /** Ultima lettura; la prima richiesta avvia la registrazione, che poi non si ferma piu'. */
    val latest: StateFlow<LlmTaskSnapshot?> = snapshots()
        .onEach { record(it) }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Lazily, null)

    /** Scrive in [target] intestazione, letture, generazioni e logcat del processo; false se non riesce. */
    suspend fun export(target: Uri): Boolean = withContext(Dispatchers.IO) {
        val recorded = synchronized(samples) { samples.toList() }
        val report = buildTaskReport(header(), recorded, engine.stats.value.history, readLogcat())
        try {
            context.contentResolver.openOutputStream(target)?.use { it.write(report.toByteArray()) } != null
        } catch (_: IOException) {
            false
        }
    }

    private fun record(snapshot: LlmTaskSnapshot) = synchronized(samples) {
        samples.addLast(TimedSnapshot(System.currentTimeMillis(), snapshot))
        if (samples.size > MAX_SAMPLES) samples.removeFirst()
    }

    private fun header(): List<String> {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val runtime = engine.stats.value
        return listOf(
            "app: ${context.packageName} $version",
            "device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "cores: ${Runtime.getRuntime().availableProcessors()}",
            "exported: ${Instant.now()}",
            "model: ${runtime.loadedModelId ?: "none"}, load_ms: ${runtime.loadTimeMs ?: ""}",
            cpuBackendHeader(engine.cpuBackend.value, CpuBackendOverride.available(context)),
        )
    }

    // Un'app puo' leggere il logcat del proprio processo senza permessi.
    private fun readLogcat(): String = try {
        ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}", "-t", LOGCAT_LINES.toString())
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().use { it.readText() }
    } catch (_: IOException) {
        "logcat not available\n"
    }

    private fun snapshots(): Flow<LlmTaskSnapshot> = flow {
        var lastCpuMs = Process.getElapsedCpuTime()
        var lastWallMs = SystemClock.elapsedRealtime()
        while (true) {
            delay(SAMPLE_INTERVAL_MS)
            val cpuMs = Process.getElapsedCpuTime()
            val wallMs = SystemClock.elapsedRealtime()
            emit(sample(cpuPercent(cpuMs - lastCpuMs, wallMs - lastWallMs)))
            lastCpuMs = cpuMs
            lastWallMs = wallMs
        }
    }.flowOn(Dispatchers.IO)

    private fun sample(cpu: Float): LlmTaskSnapshot {
        val processMemory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val systemMemory = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
        return LlmTaskSnapshot(
            models = LlmModelCatalog.ALL.filter { modelManager.isDownloaded(it) }
                .map { ModelDiskUsage(it.fileName, modelManager.sizeOnDisk(it)) },
            freeStorageBytes = modelManager.availableStorageBytes(),
            totalPssBytes = processMemory.totalPss * BYTES_PER_KB,
            nativePssBytes = (processMemory.nativePss * BYTES_PER_KB).takeIf { it > 0 },
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            systemTotalRamBytes = systemMemory.totalMem,
            systemAvailableRamBytes = systemMemory.availMem,
            lowMemory = systemMemory.lowMemory,
            cpuPercent = cpu,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            threadCount = readThreadCount(),
            runtime = engine.stats.value,
            cpuBackend = engine.cpuBackend.value,
        )
    }

    private fun readThreadCount(): Int? = try {
        File("/proc/self/status").useLines { parseThreadCount(it) }
    } catch (_: IOException) {
        null
    }
}

@HiltViewModel
class LlmTaskManagerViewModel @Inject constructor(
    private val monitor: LlmTaskMonitor,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val snapshot: StateFlow<LlmTaskSnapshot?> = monitor.latest

    private val _availableCpuBackends = MutableStateFlow<List<String>>(emptyList())

    /** Varianti CPU di llama.cpp nell'APK installato, per sceglierne una a mano. */
    val availableCpuBackends: StateFlow<List<String>> = _availableCpuBackends.asStateFlow()

    private val _chosenCpuBackend = MutableStateFlow<String?>(null)

    /** Variante scelta per il prossimo avvio; null se la sceglie ggml. */
    val chosenCpuBackend: StateFlow<String?> = _chosenCpuBackend.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val available = CpuBackendOverride.available(context)
            _availableCpuBackends.value = available
            // Una scelta non piu' presente nell'APK (variante tolta da un aggiornamento) vale come automatica.
            _chosenCpuBackend.value = CpuBackendOverride.saved(context)?.takeIf { it in available }
        }
    }

    suspend fun export(target: Uri): Boolean = monitor.export(target)

    fun chooseCpuBackend(fileName: String?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { CpuBackendOverride.save(context, fileName) }
            _chosenCpuBackend.value = fileName
        }
    }

    /**
     * Riavvia l'app: ggml carica la variante CPU solo all'avvio del processo. killProcess e non Runtime.exit, che
     * eseguirebbe i distruttori delle librerie native mentre il motore puo' ancora generare.
     */
    fun restartApp() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        context.startActivity(Intent.makeRestartActivityTask(launch.component))
        Process.killProcess(Process.myPid())
    }
}
