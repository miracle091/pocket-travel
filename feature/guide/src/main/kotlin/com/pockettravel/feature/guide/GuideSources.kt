package com.pockettravel.feature.guide

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.core.net.toUri
import com.pockettravel.core.data.GuideSourceSite
import com.pockettravel.core.data.guideSourceSiteOf
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.Spacing

// Riquadro finale con le pagine Wikivoyage (e Wikipedia, per Storia e Clima delle citta'; travel.gc.ca e gov.uk per i
// consigli di viaggio dei Governi del Canada e del Regno Unito) da cui vengono le sezioni mostrate: titolo della pagina
// (dall'URL, o il nome della fonte per i link senza /wiki/) e sito, ognuna apribile; accanto al sito "tradotta
// automaticamente" per le pagine in translatedUrls. La licenza sta nella schermata Licenze.
@Composable
internal fun GuideSourcesCard(
    sourceUrls: List<String>,
    translatedUrls: Set<String>,
    onOpenSource: (url: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val wikivoyageTitle = stringResource(R.string.guide_source_title)
    val wikipediaTitle = stringResource(R.string.guide_source_title_wikipedia)
    val travelAdviceTitle = stringResource(R.string.guide_source_title_travel_gc)
    val fcdoAdviceTitle = stringResource(R.string.guide_source_title_fcdo)
    val fcdoHealthTitle = stringResource(R.string.guide_source_title_fcdo_health)
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = Spacing.l)) {
            Text(
                text = stringResource(R.string.guide_sources_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Spacing.l).semantics { heading() },
            )
            sourceUrls.forEach { url ->
                val host = url.toUri().host.orEmpty()
                val sourceTitle = when (guideSourceSiteOf(url)) {
                    GuideSourceSite.WIKIVOYAGE -> wikivoyageTitle
                    GuideSourceSite.WIKIPEDIA -> wikipediaTitle
                    GuideSourceSite.TRAVEL_GC_CA -> travelAdviceTitle
                    GuideSourceSite.FCDO -> if (isFcdoHealthPage(url)) fcdoHealthTitle else fcdoAdviceTitle
                }
                ListItem(
                    supportingContent = {
                        Text(if (url in translatedUrls) stringResource(R.string.guide_source_translated, host) else host)
                    },
                    trailingContent = { Icon(AppIcons.OpenExternal, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onOpenSource(url, sourceTitle) },
                    content = { Text(if ("/wiki/" in url) sourcePageTitle(url) else sourceTitle) },
                )
            }
        }
    }
}

// I consigli dell'FCDO citano due pagine per paese, ".../<paese>/health" per la salute e ".../<paese>/safety-and-security" per
// il resto: nelle Fonti hanno nomi diversi, altrimenti si vedrebbero due righe uguali.
internal fun isFcdoHealthPage(url: String): Boolean = url.substringBefore('#').trimEnd('/').endsWith("/health")

// "https://it.wikivoyage.org/wiki/San_Marino" -> "San Marino"; l'URL intero se non ha /wiki/.
internal fun sourcePageTitle(url: String): String =
    url.substringAfter("/wiki/", "").takeIf { it.isNotEmpty() }
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        ?.replace('_', ' ')
        ?: url
