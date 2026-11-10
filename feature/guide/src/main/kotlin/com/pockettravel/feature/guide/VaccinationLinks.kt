package com.pockettravel.feature.guide

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pockettravel.core.data.OfficialSource
import com.pockettravel.core.data.OfficialSourceTopic
import com.pockettravel.core.data.fallbackTravelAdviceSource
import com.pockettravel.core.data.officialSourcesRegistry
import com.pockettravel.core.data.travelAdviceSourceFor
import com.pockettravel.core.data.urlFor
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.Spacing
import java.util.Locale

// Pagine dei servizi citati nelle voci, per le sigle usate nei dati (vaccinationSourceName).
internal fun vaccinationSourceUrl(code: String): String? = when (code) {
    "F3" -> "https://www.who.int/groups/poliovirus-ihr-emergency-committee"
    "F6" -> "https://travel.gc.ca/travelling/health-safety/vaccines"
    "F7" -> "https://travelhealthpro.org.uk/countries"
    "F8" -> "https://www.gov.uk/foreign-travel-advice"
    "F9" -> "https://www.diplomatie.gouv.fr/fr/conseils-aux-voyageurs/"
    else -> null
}

// Paesi senza pagina sul portale dati dell'OMS (confronto del 2026-10-09 tra Locale.getISOCountries e l'elenco del
// portale): territori e dipendenze, piu' Liechtenstein, Taiwan e Vaticano.
private val WHO_SCHEDULE_MISSING = setOf(
    "AQ", "AX", "BL", "BQ", "BV", "CC", "CX", "EH", "FK", "FO", "GF", "GG", "GI", "GL", "GP", "GS", "HM", "IM",
    "IO", "JE", "LI", "MF", "MQ", "NF", "PM", "PN", "PR", "RE", "SH", "SJ", "TF", "TW", "UM", "VA", "VI", "YT",
)

/**
 * Calendario vaccinale di [country] (ISO alpha-2) sul portale dati dell'OMS, null se il portale non ha il paese.
 * Solo il link: i dati del portale hanno termini d'uso non commerciali, quindi non si copiano nell'app.
 */
internal fun vaccinationScheduleUrl(country: String): String? {
    val code = country.uppercase(Locale.ROOT)
    if (code in WHO_SCHEDULE_MISSING) return null
    // Stesso indirizzo che costruisce il portale; senza codice alpha-3 getISO3Country lancia MissingResourceException.
    val iso3 = runCatching { Locale.Builder().setRegion(code).build().isO3Country }.getOrNull()
    return iso3?.takeIf { it.isNotEmpty() }
        ?.let { "https://immunizationdata.who.int/global/wiise-detail-page/vaccination-schedule-for-country_name?ISO_3_CODE=$it" }
}

private val travelHealthProSource = OfficialSource(
    name = "TravelHealthPro (UKHSA / NaTHNaC)",
    url = "https://travelhealthpro.org.uk/countries",
    description = R.string.vacc_link_travelhealthpro,
    topic = OfficialSourceTopic.HEALTH,
)

// Sito degli esteri della nazionalita' scelta al primo avvio (pagina iniziale: il registro non ha le pagine per paese, tranne
// la pagina della destinazione su Viaggiare Sicuri), altrimenti GOV.UK, come nella Guida, con la descrizione che dice
// per chi e' scritto.
@Composable
internal fun TravelAdviceLink(nationality: String?, destination: String?, onOpenSource: (url: String, title: String) -> Unit) {
    val own = travelAdviceSourceFor(nationality?.uppercase())
    val source = own ?: fallbackTravelAdviceSource
    LinkRow(
        title = source.name,
        description = stringResource(if (own != null) R.string.emergency_travel_advice_description else R.string.emergency_travel_advice_fallback_description),
        url = source.urlFor(destination),
        onOpenSource = onOpenSource,
    )
}

// Nome ufficiale del portale, in inglese come il sito.
internal const val WHO_IMMUNIZATION_PORTAL = "WHO Immunization Data portal"

// Link a un sito esterno dentro il riquadro di una voce: nome del sito, a cosa serve, icona di apertura.
@Composable
internal fun LinkRow(title: String, description: String, url: String, onOpenSource: (url: String, title: String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = stringResource(R.string.vacc_open_source, title)) { onOpenSource(url, title) }
            .padding(vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
        }
        Box(modifier = Modifier.heightIn(min = 48.dp).width(48.dp), contentAlignment = Alignment.Center) {
            Icon(imageVector = AppIcons.OpenExternal, contentDescription = null)
        }
    }
}

@Composable
internal fun LinksCard(nationality: String?, destination: String?, onOpenSource: (url: String, title: String) -> Unit) {
    // OMS e CDC dal registro delle fonti ufficiali; Viaggiare Sicuri solo per chi ha nazionalita' italiana, sulla
    // pagina della destinazione.
    val sources = officialSourcesRegistry.filter { it.topic == OfficialSourceTopic.HEALTH && it.countries.isEmpty() } +
        travelHealthProSource +
        listOfNotNull(if (nationality.equals("IT", ignoreCase = true)) travelAdviceSourceFor("IT") else null)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = Spacing.s)) {
            Text(
                text = stringResource(R.string.vacc_links_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s).semantics { heading() },
            )
            // Divise per lingua del sito: prima quelle in italiano (domini .it, es. Viaggiare Sicuri), poi le altre, in inglese.
            val (italian, english) = sources.partition { java.net.URI(it.url).host.orEmpty().endsWith(".it") }
            listOf(R.string.vacc_links_italian to italian, R.string.vacc_links_english to english).forEach { (label, group) ->
                if (group.isEmpty()) return@forEach
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.s).semantics { heading() },
                )
                group.forEach { source ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(onClickLabel = stringResource(R.string.vacc_open_source, source.name)) { onOpenSource(source.urlFor(destination), source.name) }
                            .padding(start = Spacing.l, end = Spacing.s, top = Spacing.s, bottom = Spacing.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = source.name, style = MaterialTheme.typography.bodyLarge)
                            Text(text = stringResource(source.description), style = MaterialTheme.typography.bodyMedium)
                        }
                        Box(modifier = Modifier.heightIn(min = 48.dp).width(48.dp), contentAlignment = Alignment.Center) {
                            Icon(imageVector = AppIcons.OpenExternal, contentDescription = null)
                        }
                    }
                }
            }
        }
    }
}
