package com.pockettravel.core.data

import java.time.Instant

/*
 * Avvisi di servizio dei mezzi pubblici: i messaggi Alert di un feed GTFS-RT (gtfs-realtime.proto di google/transit,
 * Apache 2.0), con i campi che l'app mostra. I valori di cause, effect e severity_level restano i numeri del .proto.
 */

/** Un testo con le sue traduzioni (TranslatedString di GTFS-RT). */
data class TransitText(val translations: List<TransitTranslation>) {
    /**
     * La traduzione per [language] (codice a due lettere o BCP 47, senza distinguere le maiuscole), se no l'inglese, se
     * no quella senza lingua, se no la prima; null se non ce ne sono.
     */
    fun pick(language: String): String? {
        // Conta la sola lingua primaria: "it-IT" e "it" sono la stessa.
        fun byLanguage(code: String) = translations.firstOrNull { it.language?.substringBefore('-').equals(code.substringBefore('-'), ignoreCase = true) }
        return (byLanguage(language) ?: byLanguage("en") ?: translations.firstOrNull { it.language.isNullOrEmpty() } ?: translations.firstOrNull())?.text
    }
}

/** Una traduzione di [TransitText]; [language] e' null se il feed non la indica. */
data class TransitTranslation(val text: String, val language: String?)

/** Periodo in cui un avviso vale (TimeRange): un estremo mancante e' aperto. */
data class TransitActivePeriod(val start: Instant?, val end: Instant?)

/**
 * A chi si riferisce un avviso (EntitySelector): i campi che il feed non indica sono null. Per una corsa, [tripId] oppure
 * [tripRouteId] con [tripStartTime] e [tripStartDate] (i feed senza trip_id, come HSL).
 */
data class TransitInformedEntity(
    val agencyId: String? = null,
    val routeId: String? = null,
    val routeType: Int? = null,
    val stopId: String? = null,
    val directionId: Int? = null,
    val tripId: String? = null,
    val tripRouteId: String? = null,
    val tripDirectionId: Int? = null,
    val tripStartTime: String? = null,
    val tripStartDate: String? = null,
)

/** Un avviso di servizio; [cause], [effect] e [severity] sono i numeri degli enum Cause, Effect e SeverityLevel. */
data class TransitAlert(
    val activePeriods: List<TransitActivePeriod>,
    val informedEntities: List<TransitInformedEntity>,
    val cause: Int?,
    val effect: Int?,
    val severity: Int?,
    val url: TransitText?,
    val header: TransitText?,
    val description: TransitText?,
)

// Aggiunge [item] solo se la lista ha meno di [max] elementi: i limiti del decoder contro i feed ostili.
internal fun <T> MutableList<T>.addUpTo(item: T, max: Int) {
    if (size < max) add(item)
}
