package com.pockettravel.feature.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pockettravel.core.data.BoardAlert
import com.pockettravel.core.data.TransitText
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.Spacing

/**
 * La banda "Avvisi" sopra le partenze: chiusa mostra quanti sono e il titolo del primo, un tocco la apre con titolo,
 * testo e indirizzo di ognuno. Testi nella lingua dell'app, se no in inglese, se no in quella del feed; alcuni enti
 * (Tolosa, Calgary) li scrivono in HTML.
 */
@Composable
internal fun TransitAlertsBand(alerts: List<BoardAlert>) {
    if (alerts.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    val language = LocalLocale.current.platformLocale.language
    val toggleLabel = stringResource(if (open) R.string.transit_alerts_collapse else R.string.transit_alerts_expand)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClickLabel = toggleLabel) { open = !open },
    ) {
        Column(modifier = Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Icon(AppIcons.Error, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    text = pluralStringResource(R.plurals.transit_alerts_count, alerts.size, alerts.size),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(AppIcons.ExpandMore, contentDescription = null, modifier = Modifier.rotate(if (open) HALF_TURN_DEGREES else 0f))
            }
            if (!open) {
                alerts.first().header.plain(language)?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            } else {
                alerts.forEachIndexed { index, alert ->
                    if (index > 0) HorizontalDivider()
                    AlertText(alert, language)
                }
            }
        }
    }
}

@Composable
private fun AlertText(alert: BoardAlert, language: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        alert.header.plain(language)?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold) }
        alert.description.plain(language)?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
        alert.url.plain(language)?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
    }
}

// La freccia della banda aperta guarda in su.
private const val HALF_TURN_DEGREES = 180f

// Il testo nella lingua scelta, senza tag HTML; null se vuoto. Solo i testi con un tag passano dall'HTML, che
// unirebbe gli a capo dei testi semplici.
private fun TransitText?.plain(language: String): String? =
    this?.pick(language)?.let { if ('<' in it) AnnotatedString.fromHtml(it).text else it }?.trim()?.takeIf { it.isNotEmpty() }
