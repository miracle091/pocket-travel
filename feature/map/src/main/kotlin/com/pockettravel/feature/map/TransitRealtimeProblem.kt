package com.pockettravel.feature.map

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException

/** Perche' i ritardi mancano, per il messaggio sotto le partenze; DataSaver e BatterySaver hanno un pulsante alle impostazioni. */
enum class RealtimeProblem { Offline, DataSaver, BatterySaver, FeedUnreachable }

/** Lo stato del telefono nel momento di un fallimento (o dell'apertura del riquadro). */
data class RealtimeDeviceState(
    val online: Boolean,
    val metered: Boolean,
    /** Risparmio dati attivo e l'app non e' tra quelle senza limiti (RESTRICT_BACKGROUND_STATUS_ENABLED). */
    val dataSaverRestricts: Boolean,
    val powerSave: Boolean,
)

/** Una risposta HTTP d'errore dell'ente (403, 5xx...): l'ente ha risposto, la rete del telefono funziona. */
class RealtimeHttpException(val code: Int) : IOException("HTTP $code")

/**
 * La causa di un download non riuscito. Solo un errore di connessione (nome non risolto, connessione rifiutata o
 * interrotta, tempo scaduto) puo' dipendere dal telefono: una risposta HTTP d'errore o un feed illeggibile sono sempre
 * dell'ente. Per un errore di connessione si guarda lo stato del telefono: senza rete, risparmio dati (solo su rete a
 * consumo), risparmio energetico; se nessuno e' attivo e' l'ente.
 */
internal fun classifyRealtimeFailure(error: Throwable, device: RealtimeDeviceState): RealtimeProblem = when {
    !isConnectionError(error) -> RealtimeProblem.FeedUnreachable
    !device.online -> RealtimeProblem.Offline
    device.metered && device.dataSaverRestricts -> RealtimeProblem.DataSaver
    device.powerSave -> RealtimeProblem.BatterySaver
    else -> RealtimeProblem.FeedUnreachable
}

// InterruptedIOException comprende i tempi scaduti (SocketTimeoutException, il timeout della chiamata di OkHttp).
private fun isConnectionError(error: Throwable): Boolean =
    error is UnknownHostException || error is SocketException || error is InterruptedIOException
