package com.pockettravel.core.sync

import android.content.Context
import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.data.PackageKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Una voce della coda: i pacchetti [kinds] di una regione, con la voce del catalogo e la rete che vuole. */
@Serializable
internal data class QueuedDownload(
    val key: String,
    val regionId: String,
    val displayName: String,
    val entryJson: String,
    val kinds: Set<PackageKind>,
    // Solo su una rete senza limiti di traffico: con la sola rete mobile la voce si salta e aspetta.
    val requiresWifi: Boolean,
    // Le citta' riscaricate dopo un cambio di lingua: un cambio successivo le toglie.
    val citiesReload: Boolean = false,
    // Tentativi gia' falliti per un errore di rete.
    val attempts: Int = 0,
)

/** Lo stato della coda: la voce in download, quelle in attesa e il conto di quelle finite dall'ultima coda vuota. */
internal data class QueueState(
    val current: QueuedDownload? = null,
    val pending: List<QueuedDownload> = emptyList(),
    val done: Int = 0,
    val failedRegionIds: List<String> = emptyList(),
) {
    // Il «M» di «N di M»: le finite, quella in corso e quelle in attesa.
    val total: Int get() = done + failedRegionIds.size + pending.size + (if (current != null) 1 else 0)

    // Le regioni con una voce in corso o in attesa (non quelle fallite).
    val regionIds: Set<String> get() = (listOfNotNull(current) + pending).mapTo(mutableSetOf()) { it.regionId }
}

@Serializable
private data class QueueFile(
    val items: List<QueuedDownload> = emptyList(),
    val done: Int = 0,
    val failedRegionIds: List<String> = emptyList(),
)

/**
 * La coda dei download delle regioni, una voce alla volta, salvata su file: dopo la morte del processo il worker riparte
 * dalla voce che stava scaricando. Chi accoda avvia il worker solo quando [add] lo chiede: mentre il worker scarica, la
 * voce nuova la prende lui.
 */
@Singleton
internal class RegionDownloadQueue(private val file: File, private val ioDispatcher: CoroutineDispatcher) {
    @Inject constructor(@ApplicationContext context: Context, @IoDispatcher ioDispatcher: CoroutineDispatcher) :
        this(File(context.noBackupFilesDir, FILE_NAME), ioDispatcher)

    private val mutex = Mutex()
    private var loaded = false
    private var items = emptyList<QueuedDownload>()
    private var done = 0
    private var failedRegionIds = emptyList<String>()
    private var currentKey: String? = null

    // Vero mentre il worker tiene una voce: tra la sua ultima voce e la fine il lavoro va riavviato da chi accoda.
    private var active = false
    private val _state = MutableStateFlow(QueueState())

    /** Vuoto finche' la coda non si legge dal file: al primo uso o con [load]. */
    val state: StateFlow<QueueState> = _state.asStateFlow()

    suspend fun load() {
        if (!mutex.withLock { loaded }) change {}
    }

    /** Accoda [kinds] di una regione; true se il worker va avviato (nessuno sta scaricando). */
    suspend fun add(
        regionId: String,
        displayName: String,
        entryJson: String,
        kinds: Set<PackageKind>,
        requiresWifi: Boolean,
        citiesReload: Boolean = false,
    ): Boolean = change {
        // Una coda nuova riparte da «1 di M».
        if (!active && items.isEmpty()) {
            done = 0
            failedRegionIds = emptyList()
        }
        val waiting = items.indexOfFirst { it.regionId == regionId && it.key != currentKey }
        items = if (waiting >= 0) {
            // La regione e' gia' in attesa: una voce sola, con la voce del catalogo piu' recente e tutti i pacchetti.
            val old = items[waiting]
            items.toMutableList().apply {
                set(
                    waiting,
                    old.copy(
                        entryJson = entryJson,
                        kinds = old.kinds + kinds,
                        requiresWifi = old.requiresWifi && requiresWifi,
                        citiesReload = old.citiesReload && citiesReload,
                    ),
                )
            }
        } else {
            items + QueuedDownload(UUID.randomUUID().toString(), regionId, displayName, entryJson, kinds, requiresWifi, citiesReload)
        }
        !active
    }

    /**
     * La prossima voce da scaricare, che diventa quella in corso; con [unmetered] falso salta quelle che vogliono il Wi-Fi.
     * Null quando non ce n'e': da qui chi accoda deve riavviare il worker.
     */
    suspend fun claimNext(unmetered: Boolean): QueuedDownload? = change {
        val next = items.firstOrNull { unmetered || !it.requiresWifi }
        currentKey = next?.key
        active = next != null
        next
    }

    /** Fine della voce [key]: tolta se installata o fallita, in fondo alla coda se da ritentare. */
    suspend fun finish(key: String, outcome: DownloadOutcome) {
        change {
            val item = items.firstOrNull { it.key == key }
            if (currentKey == key) currentKey = null
            if (item != null) {
                items = items - item
                when (outcome) {
                    DownloadOutcome.SUCCESS -> done++
                    DownloadOutcome.FAILURE, DownloadOutcome.DATA_SAVER_BLOCKED, DownloadOutcome.NETWORK_BLOCKED ->
                        failedRegionIds = failedRegionIds + item.regionId
                    DownloadOutcome.RETRY -> items = items + item.copy(attempts = item.attempts + 1)
                }
            }
        }
    }

    /** Toglie le voci della regione, anche quella in corso: il worker ne ferma il download. */
    suspend fun remove(regionId: String) {
        change {
            if (items.any { it.key == currentKey && it.regionId == regionId }) currentKey = null
            items = items.filterNot { it.regionId == regionId }
        }
    }

    /** Toglie le citta' riscaricate per una lingua di prima, anche quella in corso. */
    suspend fun removeCitiesReload() {
        change {
            if (items.any { it.key == currentKey && it.citiesReload }) currentKey = null
            items = items.filterNot { it.citiesReload }
        }
    }

    /** Svuota la coda («Annulla tutto»). */
    suspend fun clear() {
        change {
            items = emptyList()
            currentKey = null
            done = 0
            failedRegionIds = emptyList()
        }
    }

    /** Il worker ha finito, anche se fermato a meta': la prossima voce accodata lo riavvia. */
    suspend fun release() {
        change { active = false }
    }

    private suspend fun <T> change(block: () -> T): T = mutex.withLock {
        withContext(ioDispatcher) {
            if (!loaded) {
                val saved = runCatching { JSON.decodeFromString(QueueFile.serializer(), file.readText()) }.getOrNull() ?: QueueFile()
                items = saved.items
                done = saved.done
                failedRegionIds = saved.failedRegionIds
                loaded = true
            }
            save(block())
        }
    }

    private fun <T> save(result: T): T {
        // Scrittura su un file a parte poi rinominato: un processo ucciso a meta' non lascia una coda troncata.
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSON.encodeToString(QueueFile.serializer(), QueueFile(items, done, failedRegionIds)))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        _state.value = snapshot()
        return result
    }

    private fun snapshot(): QueueState {
        val current = items.firstOrNull { it.key == currentKey }
        return QueueState(current, items - listOfNotNull(current).toSet(), done, failedRegionIds)
    }

    companion object {
        const val FILE_NAME = "download-queue.json"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
