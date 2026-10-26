package com.pockettravel.core.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pockettravel.core.data.alerts.Alert
import com.pockettravel.core.data.alerts.AlertSource
import com.pockettravel.core.data.alerts.RegionAlerts

private const val METEOALARM_URL = "https://meteoalarm.org/en/live/"
private const val METEOALARM_NAME = "EUMETNET – MeteoAlarm"

// Attribuzioni richieste dalle fonti, nomi propri non tradotti.
private const val GDACS_NAME = "Global Disaster Alert and Coordination System, GDACS"
private const val USGS_NAME = "U.S. Geological Survey"

private const val NWS_NAME = "National Weather Service (NOAA)"
private const val TSUNAMI_NAME = "NOAA Tsunami Warning Centers (tsunami.gov)"

// Riga della fonte di ogni servizio nazionale, nell'ordine in cui compaiono.
private val NATIONAL_SOURCES: Map<AlertSource, @Composable () -> String> = linkedMapOf(
    AlertSource.NWS to { stringResource(R.string.alerts_source_translated, NWS_NAME) },
    AlertSource.TSUNAMI to { stringResource(R.string.alerts_source_translated, TSUNAMI_NAME) },
    AlertSource.ECCC to { stringResource(R.string.alerts_source_eccc) },
    AlertSource.GEONET to { stringResource(R.string.alerts_source_geonet) },
    AlertSource.JMA to { stringResource(R.string.alerts_source_jma) },
)

/** Allerte mostrate prima di "Mostra tutte": una nazione intera puo' averne decine (una per area). */
private const val ALERTS_SHOWN = 5

/**
 * Riquadro "Allerte in corso" della guida e del riquadro di emergenza dell'assistente: allerte dalla piu' grave, fonti
 * con l'ora dell'ultimo download ("Ultime allerte scaricate alle" senza rete), e sempre la frase che non sono
 * informazioni ufficiali. Per Meteoalarm, come chiedono i suoi termini: servizio nazionale, ora di emissione di ogni
 * allerta, collegamento a meteoalarm.org e frase sui ritardi. Un tocco su un'allerta apre la pagina della fonte.
 */
@Composable
fun AlertsCard(state: RegionAlerts, onOpenSource: (url: String, title: String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            InfoCardHeader(icon = AppIcons.Error, title = stringResource(R.string.alerts_title), iconTint = MaterialTheme.colorScheme.error)
            if (state.alerts.isEmpty()) {
                Text(stringResource(R.string.alerts_none), style = MaterialTheme.typography.bodyMedium)
            }
            val shown = if (expanded) state.alerts else state.alerts.take(ALERTS_SHOWN)
            shown.forEach { AlertRow(it, onOpenSource) }
            if (!expanded && state.alerts.size > ALERTS_SHOWN) {
                TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.alerts_show_all, state.alerts.size)) }
            }
            AlertsSources(state, onOpenSource)
            Text(
                stringResource(R.string.alerts_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun AlertRow(alert: Alert, onOpenSource: (url: String, title: String) -> Unit) {
    val url = alert.url
    val text = alertText(alert)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (url != null) Modifier.clickable { onOpenSource(url, alert.title) } else Modifier)
            .padding(vertical = Spacing.xs),
    ) {
        Text(text.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        text.lines.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        alert.issuedAt?.let {
            Text(
                timeString(it.toEpochMilli(), R.string.alerts_issued, R.string.alerts_issued_on),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AlertsSources(state: RegionAlerts, onOpenSource: (url: String, title: String) -> Unit) {
    val sources = state.alerts.map { it.source }.toSet()
    // GDACS e USGS si consultano sempre, anche quando non hanno allerte per la regione.
    val names = "$GDACS_NAME, $USGS_NAME"
    val millis = state.updatedAtMillis
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    if (state.fresh) {
        val updated = if (DateUtils.isToday(millis)) R.string.alerts_source_updated else R.string.alerts_source_updated_on
        Text(stringResource(updated, names, timeText(millis)), style = style, color = color)
    } else {
        Text(timeString(millis, R.string.alerts_offline, R.string.alerts_offline_on), style = style, color = color)
        Text(stringResource(R.string.alerts_source, names), style = style, color = color)
    }
    // Attribuzioni chieste dalle fonti nazionali, solo per quelle che hanno allerte nel riquadro.
    NATIONAL_SOURCES.filterKeys { it in sources }.values.forEach { line ->
        Text(line(), style = style, color = color)
    }
    if (AlertSource.METEOALARM in sources) {
        val sender = state.meteoalarmSender
        Text(
            if (sender != null) stringResource(R.string.alerts_source_meteoalarm, sender) else stringResource(R.string.alerts_source, METEOALARM_NAME),
            style = style,
            color = color,
        )
        Text(
            "www.meteoalarm.org",
            style = style,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.heightIn(min = 48.dp).clickable { onOpenSource(METEOALARM_URL, METEOALARM_NAME) }.padding(vertical = Spacing.s),
        )
        Text(stringResource(R.string.alerts_meteoalarm_delays), style = style, color = color)
    }
}

// [today] con l'ora ("Emessa alle 14:05"), altrimenti [otherDay] con data e ora ("Emessa il 25 ottobre alle ore 09:32").
@Composable
private fun timeString(millis: Long, today: Int, otherDay: Int): String =
    stringResource(if (DateUtils.isToday(millis)) today else otherDay, timeText(millis))

// Ora del giorno se e' oggi, altrimenti data e ora, nel formato del sistema.
@Composable
private fun timeText(millis: Long): String {
    val context = LocalContext.current
    val flags = if (DateUtils.isToday(millis)) DateUtils.FORMAT_SHOW_TIME else DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE
    return DateUtils.formatDateTime(context, millis, flags)
}
