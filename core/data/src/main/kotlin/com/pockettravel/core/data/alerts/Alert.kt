package com.pockettravel.core.data.alerts

import java.time.Instant

/** Fonte aperta da cui arriva un'allerta. */
enum class AlertSource { GDACS, USGS, METEOALARM }

/** Tipo di evento, comune alle fonti. */
enum class AlertKind { EARTHQUAKE, CYCLONE, FLOOD, VOLCANO, FIRE, DROUGHT, TSUNAMI, WEATHER, OTHER }

/** Gravita' riportata alla stessa scala per tutte le fonti, dalla meno alla piu' grave. */
enum class AlertLevel { LOW, MODERATE, HIGH, EXTREME }

/**
 * Un'allerta in corso, gia' normalizzata. Il testo e' quello inglese della fonte: l'app lo riscrive nella sua lingua
 * con le funzioni di `AlertWording.kt`.
 *
 * @property id identificativo stabile nella fonte, per togliere i doppioni.
 * @property sourceLevel livello originale della fonte (colore GDACS, gravita' CAP), se c'e'.
 * @property details descrizione breve della fonte (gravita' misurata, luogo), se c'e'.
 * @property startsAt inizio dell'evento o della validita'.
 * @property endsAt fine della validita'; nulla per gli eventi puntuali o ancora in corso.
 * @property area estensione dell'evento (un punto e' un rettangolo di lato zero); nulla se la fonte non da' coordinate.
 * @property areaName nome dell'area interessata, se la fonte lo da'.
 * @property url pagina della fonte con il dettaglio.
 * @property magnitude magnitudo di un terremoto (USGS).
 * @property feltReports segnalazioni di chi ha sentito la scossa (USGS "Did You Feel It?"), se ci sono.
 * @property issuedAt ora di emissione del messaggio (Meteoalarm, `cap:sent`), da mostrare accanto all'allerta.
 * @property impactAlert allerta PAGER sui danni stimati di un terremoto (USGS: green, yellow, orange, red), se c'e'.
 */
data class Alert(
    val id: String,
    val source: AlertSource,
    val kind: AlertKind,
    val level: AlertLevel,
    val sourceLevel: String?,
    val title: String,
    val details: String?,
    val startsAt: Instant?,
    val endsAt: Instant?,
    val area: AlertBounds?,
    val areaName: String?,
    val url: String?,
    val magnitude: Double? = null,
    val feltReports: Int? = null,
    val impactAlert: String? = null,
    val issuedAt: Instant? = null,
) {
    /** Vero se la validita' non e' finita a [now]. */
    fun isActiveAt(now: Instant): Boolean = endsAt?.isAfter(now) != false
}

/** La risposta di una fonte non si legge: rete troncata, formato cambiato o contenuto inatteso. */
class AlertParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
