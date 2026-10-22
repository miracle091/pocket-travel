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
 * peso dei feed che su rete a consumo si scaricano solo con un tocco (null se nessuno aspetta).
 */
data class TransitRealtimeUi(val updatedAt: Instant?, val loading: Boolean, val failed: Boolean, val tapSizeBytes: Long?)

/**
 * I feed GTFS-RT delle reti del tabellone aperto: li scarica solo con la rete, mai in background (li chiede il ciclo del
 * tabellone di MapRouteViewModel), e li tiene per [REALTIME_MAX_AGE]. Su rete a consumo i feed oltre
 * [LARGE_REALTIME_BYTES] si scaricano solo con un tocco ([refresh] con tapped). Senza rete nessun feed vale: il
 * tabellone resta l'orario di sempre.
 */
class TransitRealtimeLoader @Inject constructor(
    private val client: TransitRealtimeClient,
    private val connectivity: ConnectivityChecker,
    private val clock: Clock,
) {
    private class Entry(val feed: RealtimeFeed, val fetchedAt: Instant)

    private val feeds = mutableMapOf<String, Entry>()
    private val lastTry = mutableMapOf<String, Instant>()
    private val failed = mutableSetOf<String>()

    /** I feed ancora validi per [networks], per id di rete; vuoto senza rete. */
    fun usable(networks: List<TransitFeedInfo>): Map<String, RealtimeFeed> {
        if (networks.isEmpty() || !connectivity.isOnline()) return emptyMap()
        val now = clock.instant()
        return networks.mapNotNull { network ->
            feeds[network.id]?.takeIf { Duration.between(it.fetchedAt, now) <= REALTIME_MAX_AGE }?.let { network.id to it.feed }
        }.toMap()
    }

    /**
     * Scarica in parallelo i feed di [networks]: quelli piccoli (o su rete non a consumo) sempre, i grandi solo con
     * [tapped]; nessuna rete scaricata piu' spesso di [REALTIME_MIN_INTERVAL]. Senza rete non fa nulla.
     */
    suspend fun refresh(networks: List<TransitFeedInfo>, tapped: Boolean) {
        if (!connectivity.isOnline()) return
        val now = clock.instant()
        val due = networks.filter { network ->
            val url = network.realtimeUrl ?: return@filter false
            url.isNotEmpty() && (tapped || !waitsForTap(network)) &&
                lastTry[network.id]?.let { Duration.between(it, now) >= REALTIME_MIN_INTERVAL } != false
        }
        coroutineScope {
            due.map { network ->
                async {
                    lastTry[network.id] = now
                    try {
                        val feed = client.fetch(checkNotNull(network.realtimeUrl))
                        feeds[network.id] = Entry(feed, clock.instant())
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
            updatedAt = networks.mapNotNull { network -> feeds[network.id]?.takeIf { network.id in usable }?.fetchedAt }.minOrNull(),
            loading = loading,
            failed = networks.any { it.id in failed },
            tapSizeBytes = waiting.takeIf { it.isNotEmpty() }?.sumOf { it.realtimeSizeBytes ?: 0L },
        )
    }

    private fun waitsForTap(network: TransitFeedInfo): Boolean =
        (network.realtimeSizeBytes ?: 0L) > LARGE_REALTIME_BYTES && connectivity.isMetered()
}

/** Le reti con il tempo reale tra quelle del tabellone (anche quelle scadute da poco: le partenze stimate restano senza). */
fun realtimeNetworks(board: TransitBoard?): List<TransitFeedInfo> = when (board) {
    is TransitBoard.Departures -> board.feeds
    is TransitBoard.Expired, TransitBoard.NoStops, null -> emptyList()
}.filter { !it.realtimeUrl.isNullOrEmpty() }.distinctBy { it.id }

/** Oltre questo peso (Helsinki, Berlino, Praga) un feed su rete a consumo si scarica solo con un tocco. */
internal const val LARGE_REALTIME_BYTES = 1_000_000L

// Un feed piu' vecchio non dice piu' nulla sui ritardi di adesso; i feed degli enti si aggiornano ogni 20 s - 2 min.
internal val REALTIME_MAX_AGE: Duration = Duration.ofMinutes(10)

// Tocchi ravvicinati su "Aggiorna i ritardi" non riscaricano lo stesso feed.
internal val REALTIME_MIN_INTERVAL: Duration = Duration.ofSeconds(20)
