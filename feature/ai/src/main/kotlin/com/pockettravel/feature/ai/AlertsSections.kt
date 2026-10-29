package com.pockettravel.feature.ai

import android.content.Context
import android.content.res.Configuration
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.alerts.Alert
import com.pockettravel.core.data.alerts.AlertLevel
import com.pockettravel.core.data.alerts.AlertSource
import com.pockettravel.core.data.alerts.AlertsRepository
import com.pockettravel.core.data.alerts.RegionAlerts
import com.pockettravel.core.ui.AlertText
import com.pockettravel.core.ui.alertText
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/**
 * Le allerte in corso nella regione come sezione di contesto, per le domande su una catastrofe o un'allerta
 * (isDisasterQuestion), con i motori Locale e Online. Stesse allerte del riquadro di emergenza: il repository le legge
 * con la rete o dall'ultima risposta salvata. Null per le altre domande o se nessuna fonte ha mai risposto.
 */
class AlertsSections @Inject constructor(
    private val alertsRepository: AlertsRepository,
    @param:ApplicationContext private val context: Context,
    private val clock: Clock,
) {
    internal suspend fun forQuestion(regionId: String, question: String, language: String): AssistantSection? {
        val alerts = (if (isDisasterQuestion(question)) load(regionId) else null) ?: return null
        // Titoli e frase finale nella lingua del prompt, come nel riquadro delle allerte.
        val locale = Locale.forLanguageTag(language)
        val resources = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) }).resources
        return AssistantSection(
            body = alertsContext(
                alerts,
                { alertText(it, resources, locale) },
                resources.getString(com.pockettravel.core.ui.R.string.alerts_disclaimer),
                language,
                clock.zone,
                clock.instant(),
                question,
            ),
            category = GuideCategory.DA_SAPERE,
            citation = alertsCitation(alerts, language),
        )
    }

    private suspend fun load(regionId: String): RegionAlerts? = try {
        withTimeoutOrNull(ALERTS_TIMEOUT_MILLIS) { alertsRepository.alerts(regionId) }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Un errore della rete o della cache non ferma la risposta: il contesto resta senza allerte.
        null
    }

    private companion object {
        // Come le previsioni: la risposta dell'assistente non aspetta le fonti oltre questo tempo.
        const val ALERTS_TIMEOUT_MILLIS = 15_000L
    }
}

/** Allerte nel blocco: una nazione intera puo' averne decine, e il contesto del modello sul dispositivo e' corto. */
internal const val ALERTS_IN_CONTEXT = 6

// Parole della domanda confrontate con le aree delle allerte: le piu' corte ("in", "di") collegherebbero tutto.
private const val MIN_AREA_WORD_CHARS = 4
private val nonLetters = Regex("[^\\p{L}]+")

/**
 * Il blocco delle allerte per il contesto, in testo semplice: fonti e ora dell'ultimo download (o "ultime scaricate" senza
 * rete), un'allerta per riga dalla piu' grave con livello e fine della validita', poi la frase che non sono informazioni
 * ufficiali. Senza allerte lo dice, cosi' il modello non ne inventa. Se [question] nomina l'area di qualche allerta,
 * elenca solo quelle. [text] riscrive un'allerta nella lingua del prompt.
 */
internal fun alertsContext(
    alerts: RegionAlerts,
    text: (Alert) -> AlertText,
    disclaimer: String,
    language: String,
    zone: ZoneId,
    now: Instant,
    question: String,
): String {
    val en = language == "en"
    // Una domanda che nomina un'area ("in Puglia") vuole le sue allerte: le altre della regione sono solo contate, perche'
    // un elenco lungo di aree diverse confonde i modelli piccoli.
    val written = alerts.alerts.map { it to text(it) }
    val asked = folded(question).split(nonLetters).filter { it.length >= MIN_AREA_WORD_CHARS }.toSet()
    val named = written.filter { (_, words) -> (words.lines + words.title).any { line -> folded(line).split(nonLetters).any { it in asked } } }
    val listed = named.ifEmpty { written }.take(ALERTS_IN_CONTEXT)
    val rows = listed.joinToString("") { (alert, written) ->
        val details = (written.lines + alert.level.word(en) + listOfNotNull(alert.endsAt?.let { until(it, zone, now, en) }))
            .joinToString(", ")
        "\n- ${written.title} ($details)"
    }
    val more = moreAlerts(alerts.alerts.size - listed.size, elsewhere = named.isNotEmpty(), en)
    return "${alertsHeader(alerts, zone, now, en)}$rows$more\n$disclaimer."
}

// Prima riga del blocco: fonti e ora, o che non ci sono allerte.
private fun alertsHeader(alerts: RegionAlerts, zone: ZoneId, now: Instant, en: Boolean): String {
    val sources = sourceNames(alerts).joinToString(", ")
    val updated = time(Instant.ofEpochMilli(alerts.updatedAtMillis), zone, now, en)
    return when {
        alerts.alerts.isEmpty() && en -> "No active alerts in this region ($sources, updated $updated)."
        alerts.alerts.isEmpty() -> "Nessuna allerta in corso nella regione ($sources, aggiornate $updated)."
        en && alerts.fresh -> "Active alerts in this region ($sources, updated $updated):"
        en -> "Active alerts in this region ($sources, last downloaded $updated):"
        alerts.fresh -> "Allerte in corso nella regione ($sources, aggiornate $updated):"
        else -> "Allerte in corso nella regione ($sources, ultime scaricate $updated):"
    }
}

// Riga delle allerte non elencate; [elsewhere] quando l'elenco ha solo quelle dell'area della domanda.
private fun moreAlerts(others: Int, elsewhere: Boolean, en: Boolean): String = when {
    others <= 0 -> ""
    elsewhere && en -> "\n- and $others more elsewhere in the region"
    elsewhere -> "\n- e altre $others nel resto della regione"
    en -> "\n- and $others more"
    else -> "\n- e altre $others"
}

/** La citazione sotto la risposta: le fonti consultate, con quelle che hanno allerte nella regione. */
internal fun alertsCitation(alerts: RegionAlerts, language: String): String =
    (if (language == "en") "Source: alerts from " else "Fonte: allerte di ") + sourceNames(alerts).joinToString(", ")

// GDACS e USGS si consultano sempre, anche senza allerte per la regione; le altre fonti solo se ne hanno.
private fun sourceNames(alerts: RegionAlerts): List<String> {
    val present = alerts.alerts.map { it.source }.toSet() + AlertSource.GDACS + AlertSource.USGS
    return AlertSource.entries.filter { it in present }.map { it.shortName() }
}

private fun AlertSource.shortName(): String = when (this) {
    AlertSource.GDACS -> "GDACS"
    AlertSource.USGS -> "USGS"
    AlertSource.METEOALARM -> "Meteoalarm"
    AlertSource.NWS -> "NWS"
    AlertSource.TSUNAMI -> "tsunami.gov"
    AlertSource.ECCC -> "ECCC"
    AlertSource.GEONET -> "GeoNet"
    AlertSource.JMA -> "JMA"
}

private fun AlertLevel.word(en: Boolean): String = when (this) {
    AlertLevel.LOW -> "low level" to "livello basso"
    AlertLevel.MODERATE -> "moderate level" to "livello moderato"
    AlertLevel.HIGH -> "high level" to "livello alto"
    AlertLevel.EXTREME -> "extreme level" to "livello estremo"
}.let { (english, italian) -> if (en) english else italian }

// "fino alle 22:00", "until 22:00", "until 30/10 at 22:00".
private fun until(instant: Instant, zone: ZoneId, now: Instant, en: Boolean): String =
    if (en) "until " + time(instant, zone, now, en = true).removePrefix("at ").removePrefix("on ") else "fino " + time(instant, zone, now, en = false)

// "alle 14:05" oggi, "il 30/10 alle 14:05" negli altri giorni ("at 14:05", "on 30/10 at 14:05").
private fun time(instant: Instant, zone: ZoneId, now: Instant, en: Boolean): String {
    val at = instant.atZone(zone)
    val clockTime = at.format(DateTimeFormatter.ofPattern("HH:mm"))
    return if (at.toLocalDate() == now.atZone(zone).toLocalDate()) {
        if (en) "at $clockTime" else "alle $clockTime"
    } else {
        val day = "${at.dayOfMonth}/${at.monthValue}"
        if (en) "on $day at $clockTime" else "il $day alle $clockTime"
    }
}
