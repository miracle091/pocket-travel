package com.pockettravel.feature.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.pockettravel.core.data.vaccination.AgeNote
import com.pockettravel.core.data.vaccination.VaccinationItem
import com.pockettravel.core.data.vaccination.VaccinationLevel
import com.pockettravel.core.data.vaccination.VaccinationReason
import com.pockettravel.core.data.vaccination.VaccinationResult
import com.pockettravel.core.data.vaccination.vaccinationSourceName
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.CardDescription
import com.pockettravel.core.ui.InfoCardDefaults
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.countryName

// Avviso fisso in cima alla schermata, nei colori d'allerta del tema: non si chiude.
@Composable
internal fun DisclaimerCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        // un solo nodo per TalkBack, raggiungibile come titolo: e' la prima cosa da leggere
        Row(
            modifier = Modifier.padding(Spacing.l).semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.Top,
        ) {
            Icon(AppIcons.Info, contentDescription = null, modifier = Modifier.size(InfoCardDefaults.HeaderIconSize))
            Spacer(modifier = Modifier.width(Spacing.m))
            Column {
                Text(text = stringResource(R.string.vacc_disclaimer_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.vacc_disclaimer),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
                // la frase piu' importante: a capo e in grassetto
                Text(
                    text = stringResource(R.string.vacc_disclaimer_doctor),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = Spacing.s),
                )
            }
        }
    }
}

@Composable
internal fun StaleCard(result: VaccinationResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(modifier = Modifier.padding(Spacing.l), verticalAlignment = Alignment.Top) {
            Icon(AppIcons.Error, contentDescription = null)
            Spacer(modifier = Modifier.width(Spacing.s))
            val statement = result.polioStatement
            Text(
                text = if (statement != null) stringResource(R.string.vacc_stale, statement) else stringResource(R.string.vacc_stale_no_date),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// Obbligatorie / Raccomandate (con le incoraggiate) / Da valutare con il medico. Senza certificati la prima
// sezione lo dice "nei dati dell'app", con la data: mai "non serve".
internal fun LazyListScope.resultGroups(
    result: VaccinationResult,
    nationality: String?,
    destination: String?,
    onOpenSource: (url: String, title: String) -> Unit,
) {
    val required = result.items.filter { it.isCertificate() }
    val recommended = result.items.filter { it.level == VaccinationLevel.RECOMMENDED || it.level == VaccinationLevel.ENCOURAGED }
    val consider = result.items.filter { it.level == VaccinationLevel.CONSIDER }

    item(key = "required_title") { GroupTitle(R.string.vacc_required) }
    if (required.isEmpty()) {
        item(key = "required_none") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = noCertificateText(result, LocalLocale.current.platformLocale),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(Spacing.l),
                )
            }
        }
    }
    itemsIndexed(required, key = { index, _ -> "required_$index" }) { _, item -> VaccinationItemCard(item, nationality, destination, onOpenSource) }
    if (recommended.isNotEmpty()) {
        item(key = "recommended_title") { GroupTitle(R.string.vacc_recommended) }
        itemsIndexed(recommended, key = { index, _ -> "recommended_$index" }) { _, item -> VaccinationItemCard(item, nationality, destination, onOpenSource) }
    }
    if (consider.isNotEmpty()) {
        item(key = "consider_title") { GroupTitle(R.string.vacc_consider) }
        itemsIndexed(consider, key = { index, _ -> "consider_$index" }) { _, item -> VaccinationItemCard(item, nationality, destination, onOpenSource) }
    }
}

@Composable
private fun GroupTitle(titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = Spacing.s).semantics { heading() },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VaccinationItemCard(item: VaccinationItem, nationality: String?, destination: String?, onOpenSource: (url: String, title: String) -> Unit) {
    val locale = LocalLocale.current.platformLocale
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(item.vaccine.nameRes()), style = MaterialTheme.typography.titleMedium)
            LevelBadge(item)
            item.reasonText()?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            item.detailTexts().forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            val note = if (locale.language == "en") item.noteEn.ifBlank { item.noteIt } else item.noteIt.ifBlank { item.noteEn }
            if (note.isNotBlank()) CardDescription(note.trim(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.reason == VaccinationReason.POLIO_ENTRY_UNLISTED) TravelAdviceLink(nationality, destination, onOpenSource)
            val country = item.country
            val scheduleUrl = country?.let(::vaccinationScheduleUrl)
            if (item.reason == VaccinationReason.ROUTINE && country != null && scheduleUrl != null) {
                LinkRow(WHO_IMMUNIZATION_PORTAL, stringResource(R.string.vacc_schedule_link, countryName(country.uppercase())), scheduleUrl, onOpenSource)
            }
            if (item.sources.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.vacc_sources) + ":", style = MaterialTheme.typography.labelMedium)
                    FlowRow(modifier = Modifier.weight(1f)) {
                        item.sources.forEach { code ->
                            val name = vaccinationSourceName(code, en = locale.language == "en")
                            val url = vaccinationSourceUrl(code)
                            if (url != null) {
                                TextButton(onClick = { onOpenSource(url, name) }, contentPadding = PaddingValues(horizontal = Spacing.s)) { Text(name) }
                            } else {
                                Text(name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Spacing.s))
                            }
                        }
                    }
                }
            }
            item.verifiedAt?.let { Text(stringResource(R.string.vacc_verified_on, formatDate(it, locale)), style = MaterialTheme.typography.labelMedium) }
        }
    }
}

// Il livello e' sempre scritto, il colore del riquadro e' solo un rinforzo.
@Composable
private fun LevelBadge(item: VaccinationItem) {
    val (container, content) = when (item.level) {
        VaccinationLevel.REQUIRED, VaccinationLevel.REQUIRED_EXIT -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        VaccinationLevel.ENCOURAGED -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        VaccinationLevel.RECOMMENDED -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        VaccinationLevel.CONSIDER -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when {
        item.ageNote == AgeNote.BELOW_AGE -> R.string.vacc_level_below_age
        item.level == VaccinationLevel.REQUIRED -> R.string.vacc_level_required
        item.level == VaccinationLevel.REQUIRED_EXIT -> R.string.vacc_level_required_exit
        item.level == VaccinationLevel.ENCOURAGED -> R.string.vacc_level_encouraged
        item.level == VaccinationLevel.RECOMMENDED -> R.string.vacc_level_recommended
        else -> R.string.vacc_level_consider
    }
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs),
        )
    }
}
