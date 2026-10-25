package com.pockettravel.feature.ai

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.feature.ai.llamacpp.CpuBackendInfo
import com.pockettravel.feature.ai.llamacpp.CpuBackendOverride
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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
private const val TENTHS_PER_DEGREE = 10f

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
    /** PowerManager.THERMAL_STATUS_* (Android 10+): il telefono caldo rallenta la CPU e falsa le misure. */
    val thermalStatus: Int? = null,
    /** PowerManager.getThermalHeadroom (Android 11+): 1,0 = soglia di rallentamento raggiunta; null se non disponibile. */
    val thermalHeadroom: Float? = null,
    val batteryCelsius: Float? = null,
    val charging: Boolean? = null,
)

/** Una lettura del task manager con l'ora (epoch ms) in cui e' stata presa. */
data class TimedSnapshot(val atMs: Long, val snapshot: LlmTaskSnapshot)

/** Le letture del task manager che servono alla sua schermata. */
interface TaskMonitor {
    /** Ultima lettura; null finche' non ce n'e' una. */
    val latest: StateFlow<LlmTaskSnapshot?>

    /** Scrive il rapporto in [target]; false se non riesce. */
    suspend fun export(target: Uri): Boolean
}

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
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TaskMonitor {
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val samples = ArrayDeque<TimedSnapshot>()

    /** Ultima lettura; la prima richiesta avvia la registrazione, che poi non si ferma piu'. */
    override val latest: StateFlow<LlmTaskSnapshot?> = snapshots()
        .onEach { record(it) }
        .stateIn(CoroutineScope(SupervisorJob() + ioDispatcher), SharingStarted.Lazily, null)

    /** Scrive in [target] intestazione, letture, generazioni e logcat del processo; false se non riesce. */
    override suspend fun export(target: Uri): Boolean = withContext(ioDispatcher) {
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
            socHeader(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
                Build.HARDWARE,
            ),
            cpuFrequencyHeader(readMaxCpuFrequenciesKhz()),
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
    }.flowOn(ioDispatcher)

    private fun sample(cpu: Float): LlmTaskSnapshot {
        val processMemory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val systemMemory = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
        val battery = readBattery()
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
            thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) powerManager?.currentThermalStatus else null,
            // Al piu' una chiamata al secondo (il passo di campionamento): piu' spesso il sistema risponde NaN.
            thermalHeadroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                powerManager?.getThermalHeadroom(0)?.takeUnless { it.isNaN() }
            } else {
                null
            },
            batteryCelsius = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }?.let { it / TENTHS_PER_DEGREE },
            charging = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.takeIf { it >= 0 }?.let { it != 0 },
        )
    }

    // Intent "sticky": nessun ricevitore resta registrato, si legge l'ultimo stato della batteria.
    private fun readBattery(): Intent? = ContextCompat.registerReceiver(
        context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
    )

    // cpuinfo_max_freq di ogni core in kHz, leggibile da tutte le app; null per i core senza cpufreq (emulatore x86).
    private fun readMaxCpuFrequenciesKhz(): List<Int?> = (0 until Runtime.getRuntime().availableProcessors()).map { core ->
        try {
            File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq").readText().trim().toIntOrNull()
        } catch (_: IOException) {
            null
        }
    }

    private fun readThreadCount(): Int? = try {
        File("/proc/self/status").useLines { parseThreadCount(it) }
    } catch (_: IOException) {
        null
    }
}

@HiltViewModel
class LlmTaskManagerViewModel @Inject constructor(
    private val monitor: TaskMonitor,
    private val wordListsOverride: WordListsOverride,
    private val cpuBackends: CpuBackendStore,
    private val appRestarter: AppRestarter,
    conversationMonitor: ConversationMonitor,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    val snapshot: StateFlow<LlmTaskSnapshot?> = monitor.latest

    /** La conversazione aperta nella schermata dell'assistente, se ce n'e' una. */
    val conversation: StateFlow<ConversationDebugInfo?> = conversationMonitor.current

    private val _availableCpuBackends = MutableStateFlow<List<String>>(emptyList())

    /** Varianti CPU di llama.cpp nell'APK installato, per sceglierne una a mano. */
    val availableCpuBackends: StateFlow<List<String>> = _availableCpuBackends.asStateFlow()

    private val _chosenCpuBackend = MutableStateFlow<String?>(null)

    /** Variante scelta per il prossimo avvio; null se la sceglie ggml. */
    val chosenCpuBackend: StateFlow<String?> = _chosenCpuBackend.asStateFlow()

    private val _wordLists = MutableStateFlow(true)

    /** Liste di parole accese nella scelta del contesto (WordListsOverride). */
    val wordLists: StateFlow<Boolean> = _wordLists.asStateFlow()

    init {
        viewModelScope.launch(ioDispatcher) {
            _wordLists.value = wordListsOverride.enabled()
            val available = cpuBackends.available()
            _availableCpuBackends.value = available
            // Una scelta non piu' presente nell'APK (variante tolta da un aggiornamento) vale come automatica.
            _chosenCpuBackend.value = cpuBackends.saved()?.takeIf { it in available }
        }
    }

    suspend fun export(target: Uri): Boolean = monitor.export(target)

    fun setWordLists(enabled: Boolean) {
        viewModelScope.launch {
            withContext(ioDispatcher) { wordListsOverride.save(enabled) }
            _wordLists.value = enabled
        }
    }

    fun chooseCpuBackend(fileName: String?) {
        viewModelScope.launch {
            withContext(ioDispatcher) { cpuBackends.save(fileName) }
            _chosenCpuBackend.value = fileName
        }
    }

    /** Riavvia l'app: ggml carica la variante CPU solo all'avvio del processo. */
    fun restartApp() = appRestarter.restart()
}
