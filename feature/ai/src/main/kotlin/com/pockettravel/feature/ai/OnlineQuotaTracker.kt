package com.pockettravel.feature.ai

import androidx.core.content.edit
import com.pockettravel.core.data.PreferencesFactory
import com.pockettravel.core.sync.OnlineModelEntry
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Modelli online con la quota giornaliera finita, fino all'ora in cui torna: in Automatico si saltano, e i pulsanti
 * del messaggio non li propongono. Si salvano con l'impronta della chiave API del servizio (SHA-256 troncato, mai la
 * chiave): sopravvivono al riavvio, ma non a una chiave diversa, perche' la quota e' dell'account, ne' al ritorno della quota.
 */
@Singleton
class OnlineQuotaTracker(
    preferencesFactory: PreferencesFactory,
    private val fingerprint: (OnlineProvider) -> String?,
) {
    @Inject
    constructor(preferencesFactory: PreferencesFactory, settings: AiSettingsStore) :
        this(preferencesFactory, { provider -> settings.apiKeyFingerprint(provider) })

    private val prefs = preferencesFactory.open(PREFERENCES_NAME)

    /**
     * Segna [model] esaurito e restituisce quando torna: a [resetAtMillis], se il servizio l'ha detto, altrimenti alla
     * mezzanotte di Gemini. Senza chiave non salva nulla.
     */
    fun markExhausted(provider: OnlineProvider, model: String, nowMillis: Long, resetAtMillis: Long? = null, daily: Boolean = true): Long {
        val resetAt = resetAtMillis ?: dailyQuotaResetMillis(nowMillis)
        val kind = if (daily) KIND_DAILY else KIND_REQUESTS
        fingerprint(provider)?.let { print -> prefs.edit { putString(key(provider, model), listOf(print, resetAt, kind).joinToString(SEPARATOR)) } }
        return resetAt
    }

    /** Quando torna la quota di [model], o null se non e' esaurita. Un salvataggio scaduto o di un'altra chiave si scarta. */
    fun resetAtMillis(provider: OnlineProvider, model: String, nowMillis: Long): Long? = exhaustion(provider, model, nowMillis)?.resetAtMillis

    /** Come [resetAtMillis], con il tipo di limite. Un salvataggio senza tipo (di prima) e' la quota giornaliera. */
    fun exhaustion(provider: OnlineProvider, model: String, nowMillis: Long): Exhaustion? {
        val name = key(provider, model)
        val stored = prefs.getString(name, null)?.split(SEPARATOR)?.takeIf { it.size in 2..3 }
        val resetAt = stored?.get(1)?.toLongOrNull()?.takeIf { it > nowMillis && stored[0] == fingerprint(provider) }
        if (resetAt == null && stored != null) prefs.edit { remove(name) }
        return resetAt?.let { Exhaustion(it, daily = stored.getOrNull(2) != KIND_REQUESTS) }
    }

    /** Quando tornano le richieste di un modello e se il limite e' la quota giornaliera ([daily]) o quello delle intestazioni. */
    class Exhaustion(val resetAtMillis: Long, val daily: Boolean)

    private fun key(provider: OnlineProvider, model: String) = "${provider.name}/$model"

    private companion object {
        const val PREFERENCES_NAME = "online_quota"
        const val SEPARATOR = "|"
        const val KIND_DAILY = "d"
        const val KIND_REQUESTS = "r"
    }
}

private const val FINGERPRINT_BYTES = 8

/** Impronta di una chiave API: i primi 8 byte del suo SHA-256, in esadecimale; null senza chiave. */
internal fun apiKeyFingerprint(apiKey: String?): String? = apiKey?.takeIf { it.isNotBlank() }?.let { key ->
    MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(FINGERPRINT_BYTES).joinToString("") { "%02x".format(it) }
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
    val resetAt = exhaustion(state.onlineProvider, error.model, nowMillis)?.resetAtMillis ?: error.resetAtMillis ?: dailyQuotaResetMillis(nowMillis)
    return ChatNotice.QuotaExhausted(error.model, resetAt, state.modelChoices(exhaustedModels(state, nowMillis)), error.notInFreePlan, error.daily)
}

/** Il testo secondo il limite raggiunto: la quota giornaliera di Gemini o il limite di richieste delle intestazioni. */
internal fun ChatNotice.QuotaExhausted.textRes(): Int = if (daily) R.string.ai_error_daily_quota else R.string.ai_error_request_limit

internal fun ChatNotice.ModelSwitched.textRes(): Int =
    if (daily) R.string.ai_notice_model_switched else R.string.ai_notice_model_switched_request_limit
