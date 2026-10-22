package com.pockettravel.feature.map

import com.pockettravel.core.data.RealtimeFeed
import com.pockettravel.core.data.TransitBoard
import com.pockettravel.core.data.TransitFeedInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * Stato del tempo reale sotto il tabellone: [updatedAt] l'ora del download piu' vecchio tra le reti della fermata
 * (null se nessuno e' ancora arrivato), [failed] se l'ultimo tentativo di una rete non e' andato, [tapSizeBytes] il
 * peso dei feed che su rete a consumo si scaricano solo con un tocco (null se nessuno aspetta), [paused] se
 * l'aggiornamento automatico e' fermo perche' il riquadro e' aperto da [REALTIME_IDLE_PAUSE] senza un tocco.
 */
data class TransitRealtimeUi(
    val updatedAt: Instant?,
    val loading: Boolean,
    val failed: Boolean,
    val tapSizeBytes: Long?,
    val paused: Boolean = false,
)

/**
 * I feed GTFS-RT delle reti del tabellone aperto: li scarica solo con la rete, mai in background (li chiede il ciclo del
 * tabellone di MapRouteViewModel), e li tiene per [REALTIME_MAX_AGE]. Senza rete nessun feed vale: il tabellone resta
 * l'orario di sempre. Per non consumare troppi dati con il riquadro aperto a lungo:
 * - ogni rete si riscarica da sola al piu' ogni [realtimeInterval]: piu' il feed pesa (l'ultimo download misurato, se no
 *   la stima del catalogo), piu' si aspetta, il doppio su rete a consumo;
 * - su rete a consumo i feed oltre [LARGE_REALTIME_BYTES] si scaricano solo con un tocco;
 * - dopo [REALTIME_IDLE_PAUSE] senza un'apertura del riquadro ne' un tocco ([touch]) l'aggiornamento automatico si ferma.
 * Un tocco su "Aggiorna i ritardi" ([refresh] con tapped) scarica tutto, salvo un altro tocco da meno di
 * [REALTIME_MIN_INTERVAL].
 */
class TransitRealtimeLoader @Inject constructor(
    private val client: TransitRealtimeClient,
    private val connectivity: ConnectivityChecker,
    private val clock: Clock,
) {
    // [at]: l'ora dei dati, la piu' vecchia tra il download e l'header del feed (un ente fermo serve un feed vecchio con 200).
    private class Entry(val feed: RealtimeFeed, fetchedAt: Instant) {
        val at: Instant = feed.timestamp?.takeIf { it < fetchedAt } ?: fetchedAt
    }

    private val feeds = mutableMapOf<String, Entry>()
    private val lastTry = mutableMapOf<String, Instant>()
    private val failed = mutableSetOf<String>()

    // Byte scaricati (compressi) nell'ultimo download di ogni rete.
    private val lastBytes = mutableMapOf<String, Long>()
    private var lastTouch: Instant = clock.instant()

    /** Il riquadro si e' aperto o l'utente ha toccato "Aggiorna i ritardi": l'aggiornamento automatico riparte. */
    fun touch() {
        lastTouch = clock.instant()
    }

    /** I feed ancora validi per [networks], per id di rete; vuoto senza rete. */
    fun usable(networks: List<TransitFeedInfo>): Map<String, RealtimeFeed> {
        if (networks.isEmpty() || !connectivity.isOnline()) return emptyMap()
        val now = clock.instant()
        return networks.mapNotNull { network ->
            feeds[network.id]?.takeIf { Duration.between(it.at, now) <= REALTIME_MAX_AGE }?.let { network.id to it.feed }
        }.toMap()
    }

    /**
     * Scarica in parallelo i feed di [networks] che ne hanno bisogno: con [tapped] tutti (salvo un tocco ravvicinato); se
     * no, se l'aggiornamento automatico non e' in pausa, quelli non in attesa di un tocco il cui ultimo tentativo e' piu'
     * vecchio di [realtimeInterval]. Senza rete non fa nulla.
     */
    suspend fun refresh(networks: List<TransitFeedInfo>, tapped: Boolean, onDownload: () -> Unit = {}) {
        if (!connectivity.isOnline()) return
        val now = clock.instant()
        if (tapped) touch()
        if (!tapped && paused(now)) return
        val due = networks.filter { isDue(it, tapped, now) }
        if (due.isNotEmpty()) onDownload()
        coroutineScope {
            due.map { network ->
                async {
                    lastTry[network.id] = now
                    try {
                        val download = client.fetch(checkNotNull(network.realtimeUrl))
                        feeds[network.id] = Entry(download.feed, clock.instant())
                        lastBytes[network.id] = download.bytes
                        failed -= network.id
                    } catch (e: CancellationException) {
                        // Download interrotto (riquadro chiuso): la fermata dopo della stessa rete lo riprova subito.
                        lastTry -= network.id
                        throw e
                    } catch (_: Exception) {
                        // Ente irraggiungibile o feed illeggibile: il tabellone resta con l'ultimo feed valido o l'orario.
                        failed += network.id
                    }
                }
            }.awaitAll()
        }
    }

    /** Lo stato da mostrare per [networks]; null se nessuna ha il tempo reale o non c'e' rete. */
    fun ui(networks: List<TransitFeedInfo>, loading: Boolean): TransitRealtimeUi? {
        if (networks.isEmpty() || !connectivity.isOnline()) return null
        val usable = usable(networks)
        val waiting = networks.filter { it.id !in usable && waitsForTap(it) }
        return TransitRealtimeUi(
            updatedAt = networks.mapNotNull { network -> feeds[network.id]?.takeIf { network.id in usable }?.at }.minOrNull(),
            loading = loading,
            failed = networks.any { it.id in failed },
            tapSizeBytes = waiting.takeIf { it.isNotEmpty() }?.sumOf { sizeOf(it) ?: 0L },
            paused = paused(clock.instant()),
        )
    }

    private fun isDue(network: TransitFeedInfo, tapped: Boolean, now: Instant): Boolean {
        val since = lastTry[network.id]?.let { Duration.between(it, now) }
        return when {
            network.realtimeUrl.isNullOrEmpty() -> false
            tapped -> since == null || since >= REALTIME_MIN_INTERVAL
            else -> !waitsForTap(network) && (since == null || since >= interval(network))
        }
    }

    private fun paused(now: Instant) = Duration.between(lastTouch, now) > REALTIME_IDLE_PAUSE

    // Null se ne' il catalogo ne' un download ne hanno misurato il peso.
    private fun sizeOf(network: TransitFeedInfo): Long? = lastBytes[network.id] ?: network.realtimeSizeBytes

    private fun waitsForTap(network: TransitFeedInfo): Boolean = connectivity.isMetered() && (sizeOf(network)?.let { it > LARGE_REALTIME_BYTES } ?: true)

    private fun interval(network: TransitFeedInfo): Duration = realtimeInterval(sizeOf(network) ?: 0L, connectivity.isMetered())
}

/**
 * Ogni quanto si riscarica da solo un feed di [bytes] byte: un minuto ogni [REALTIME_BYTES_PER_MINUTE] (Berlino, 2,25 MB:
 * 4,5 minuti; Renfe, 23 KB: 1 minuto), il doppio su rete a consumo, tra [REALTIME_MIN_AUTO_INTERVAL] e
 * [REALTIME_MAX_AUTO_INTERVAL].
 */
internal fun realtimeInterval(bytes: Long, metered: Boolean): Duration {
    val seconds = bytes * SECONDS_PER_MINUTE / REALTIME_BYTES_PER_MINUTE * (if (metered) 2 else 1)
    return Duration.ofSeconds(seconds).coerceIn(REALTIME_MIN_AUTO_INTERVAL, REALTIME_MAX_AUTO_INTERVAL)
}

/** Le reti con il tempo reale tra quelle del tabellone (anche quelle scadute da poco: le partenze stimate restano senza). */
fun realtimeNetworks(board: TransitBoard?): List<TransitFeedInfo> = when (board) {
    is TransitBoard.Departures -> board.feeds
    is TransitBoard.Expired, TransitBoard.NoStops, null -> emptyList()
}.filter { !it.realtimeUrl.isNullOrEmpty() }.distinctBy { it.id }

/**
 * Tra [realtimeNetworks] quelle con una partenza entro [REALTIME_HORIZON_MINUTES] (anche tra le successive): i ritardi
 * delle partenze piu' lontane cambiano comunque prima di allora, e il feed di SNCF copre solo l'ora successiva.
 */
fun realtimeNetworksDue(board: TransitBoard?): List<TransitFeedInfo> {
    val departures = (board as? TransitBoard.Departures)?.items.orEmpty().flatMap { listOf(it) + it.later }
    val near = departures.filter { it.inMinutes <= REALTIME_HORIZON_MINUTES }.mapNotNullTo(mutableSetOf()) { it.network }
    return realtimeNetworks(board).filter { it.id in near }
}

/** Oltre questo peso (Berlino, Bordeaux, Tolosa) un feed su rete a consumo si scarica solo con un tocco. */
internal const val LARGE_REALTIME_BYTES = 1_000_000L

// Un feed piu' vecchio non dice piu' nulla sui ritardi di adesso; i feed degli enti si aggiornano ogni 20 s - 2 min.
internal val REALTIME_MAX_AGE: Duration = Duration.ofMinutes(10)

// Tocchi ravvicinati su "Aggiorna i ritardi" non riscaricano lo stesso feed.
internal val REALTIME_MIN_INTERVAL: Duration = Duration.ofSeconds(20)

// Aggiornamento automatico: un minuto di attesa ogni mezzo MB di feed, tra 1 e 7 minuti (sotto REALTIME_MAX_AGE, cosi'
// tra un download e l'altro il feed precedente vale ancora).
private const val REALTIME_BYTES_PER_MINUTE = 500_000L
private const val SECONDS_PER_MINUTE = 60L
internal val REALTIME_MIN_AUTO_INTERVAL: Duration = Duration.ofMinutes(1)
internal val REALTIME_MAX_AUTO_INTERVAL: Duration = Duration.ofMinutes(7)

// Riquadro aperto senza tocchi da piu' di cosi' (telefono lasciato sul tavolo): l'aggiornamento automatico si ferma.
internal val REALTIME_IDLE_PAUSE: Duration = Duration.ofMinutes(15)

// Solo le reti con una partenza entro questi minuti scaricano il feed da sole.
internal const val REALTIME_HORIZON_MINUTES = 60
