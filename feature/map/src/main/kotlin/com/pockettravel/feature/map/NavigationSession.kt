package com.pockettravel.feature.map

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cosa mostra la notifica della guida. [turn] null = percorso ancora in calcolo (restano meta e
 * "Termina"). [street]: la strada in cui si entra con [turn], se la mappa l'ha trovata.
 */
data class NavigationSnapshot(
    val destinationName: String,
    val turn: TurnInstruction?,
    val distanceToNextMeters: Double,
    val remainingMeters: Double,
    val remainingSeconds: Double,
    val street: String? = null,
    // Nessuna posizione GPS ancora: la notifica lo dice invece di "Calcolo del percorso".
    val waitingForFix: Boolean = false,
)

/**
 * Ponte tra il [NavigationViewModel], che resta il proprietario di GPS e tracking, e il
 * [NavigationService], che tiene solo l'app in primo piano (senza, Android limita le posizioni a
 * schermo spento) e disegna la notifica. Il ViewModel scrive [snapshot] (null = nessuna guida: il
 * servizio si ferma); dalla notifica tornano la richiesta di "Termina" e quella di aprire il Navigatore.
 */
@Singleton
class NavigationSession @Inject constructor() {
    private val _snapshot = MutableStateFlow<NavigationSnapshot?>(null)
    val snapshot: StateFlow<NavigationSnapshot?> = _snapshot.asStateFlow()

    // Eventi senza replay: una richiesta vecchia non deve rieseguirsi alla prossima raccolta.
    private val _stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** "Termina" toccato nella notifica: il ViewModel chiude la guida. */
    val stopRequests: SharedFlow<Unit> = _stopRequests.asSharedFlow()

    private val _openRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Notifica toccata con l'app aperta: l'hub della regione porta in primo piano il Navigatore. */
    val openRequests: SharedFlow<Unit> = _openRequests.asSharedFlow()

    fun update(snapshot: NavigationSnapshot) {
        _snapshot.value = snapshot
    }

    fun clear() {
        _snapshot.value = null
    }

    fun requestStop() {
        _stopRequests.tryEmit(Unit)
    }

    fun requestOpen() {
        _openRequests.tryEmit(Unit)
    }
}

/** Distanza come la mostra la guida: a decine di metri sotto il chilometro, sopra in km. */
internal sealed interface DistanceLabel {
    data class Meters(val value: Int) : DistanceLabel
    data class Kilometers(val value: Double) : DistanceLabel
}

internal fun distanceLabel(meters: Double): DistanceLabel {
    // Arrotondato prima del confronto: 996 m sono gia' "1,0 km", non "1000 m".
    val rounded = (meters / 10).roundToInt() * 10
    return if (rounded < 1_000) DistanceLabel.Meters(rounded) else DistanceLabel.Kilometers(meters / 1_000)
}

/** Secondi che restano: la durata del percorso in proporzione ai metri ancora da fare. */
internal fun remainingSeconds(route: Route, remainingMeters: Double): Double =
    if (route.distanceMeters > 0) route.durationSeconds * remainingMeters / route.distanceMeters else 0.0

/** Minuto (dall'epoca) in cui si arriva, da adesso piu' [remainingSeconds]: l'ora si mostra nel formato del telefono. */
internal fun arrivalMinute(nowMillis: Long, remainingSeconds: Double): Long =
    nowMillis / 60_000 + (remainingSeconds / 60).roundToInt()

/** Minuti restanti per la notifica: sempre almeno uno. */
internal fun remainingMinutes(remainingSeconds: Double): Int = (remainingSeconds / 60).roundToInt().coerceAtLeast(1)

/** Intervallo minimo fra due aggiornamenti della notifica: piu' spesso e' inutile e il sistema le limita. */
internal const val NOTIFICATION_MIN_INTERVAL_MILLIS = 1_000L

/** Quanto aspettare prima di aggiornare la notifica, dato l'ultimo aggiornamento: 0 se e' gia' ora. */
internal fun throttleWaitMillis(lastPostMillis: Long?, nowMillis: Long, intervalMillis: Long = NOTIFICATION_MIN_INTERVAL_MILLIS): Long =
    if (lastPostMillis == null) 0L else (lastPostMillis + intervalMillis - nowMillis).coerceIn(0L, intervalMillis)
