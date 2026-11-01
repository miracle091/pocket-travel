package com.pockettravel.feature.ai

import com.pockettravel.core.sync.OnlineModelEntry
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Modelli online con la quota giornaliera finita, fino all'ora in cui torna: in Automatico si saltano, e i pulsanti
 * del messaggio non li propongono. Solo in memoria: dopo un riavvio costa al massimo una richiesta rifiutata in piu'.
 */
@Singleton
class OnlineQuotaTracker @Inject constructor() {
    private val exhausted = ConcurrentHashMap<String, Long>()

    /** Segna [model] esaurito e restituisce quando torna la sua quota. */
    fun markExhausted(provider: OnlineProvider, model: String, nowMillis: Long): Long =
        dailyQuotaResetMillis(nowMillis).also { exhausted[key(provider, model)] = it }

    /** Quando torna la quota di [model], o null se non e' esaurita. */
    fun resetAtMillis(provider: OnlineProvider, model: String, nowMillis: Long): Long? =
        exhausted[key(provider, model)]?.takeIf { it > nowMillis }

    private fun key(provider: OnlineProvider, model: String) = "${provider.name}/$model"
}

private val QUOTA_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")

/**
 * Quando torna la quota giornaliera: Gemini la azzera a mezzanotte, ora del Pacifico (pagina dei limiti di Gemini,
 * letta il 2026-11-01), cioe' alle 9:00 in Italia (le 8:00 nelle settimane in cui solo uno dei due paesi ha l'ora legale).
 */
internal fun dailyQuotaResetMillis(nowMillis: Long): Long =
    Instant.ofEpochMilli(nowMillis).atZone(QUOTA_ZONE).toLocalDate().plusDays(1)
        .atStartOfDay(QUOTA_ZONE).toInstant().toEpochMilli()

/**
 * In Automatico, i modelli da provare in ordine: quello di Automatico ([auto]), poi gli altri di [models] dal piu'
 * economico, che di solito ha la quota gratuita piu' larga ed e' il ripiego piu' leggero.
 */
internal fun automaticCandidates(auto: String, models: List<OnlineModelEntry>): List<String> =
    listOf(auto) + models.filter { it.id != auto }.sortedBy { it.inputUsdPerMTok }.map { it.id }

/** I modelli proposti del servizio di [state] con la quota giornaliera finita adesso. */
internal fun OnlineQuotaTracker.exhaustedModels(state: AiUiState, nowMillis: Long): Set<String> =
    state.onlineModelOptions.map { it.id }.filter { resetAtMillis(state.onlineProvider, it, nowMillis) != null }.toSet()

/** La quota finita di [error], con le scelte per rifare la domanda; null per gli altri errori. */
internal fun OnlineQuotaTracker.quotaNotice(error: Exception, state: AiUiState, nowMillis: Long): ChatNotice.QuotaExhausted? {
    if (error !is OnlineDailyQuotaException) return null
    val resetAt = resetAtMillis(state.onlineProvider, error.model, nowMillis) ?: dailyQuotaResetMillis(nowMillis)
    return ChatNotice.QuotaExhausted(error.model, resetAt, state.modelChoices(exhaustedModels(state, nowMillis)), error.notInFreePlan)
}
