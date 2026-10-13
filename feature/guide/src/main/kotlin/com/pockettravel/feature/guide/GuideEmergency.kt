package com.pockettravel.feature.guide

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pockettravel.core.data.DiplomaticMission
import com.pockettravel.core.data.EmbassyEntry
import com.pockettravel.core.data.EmergencyNumbers
import com.pockettravel.core.data.MissionKind
import com.pockettravel.core.data.OfficialSource
import com.pockettravel.core.data.Poi
import com.pockettravel.core.data.fallbackTravelAdviceSource
import com.pockettravel.core.data.mergeEmbassies
import com.pockettravel.core.data.nearbyEmbassies
import com.pockettravel.core.data.travelAdviceSourceFor
import com.pockettravel.core.data.urlFor
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.CardDescription
import com.pockettravel.core.ui.InfoCardHeader
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.countryName
import com.pockettravel.core.ui.safeWebUrl
import java.util.Locale
import kotlin.math.roundToInt

// numbers nullo: la regione non ha un numero di emergenza centralizzato, e il riquadro lo dichiara.
@Composable
internal fun EmergencyNumbersCard(
    numbers: EmergencyNumbers?,
    embassiesCountry: String?,
    destination: String?,
    embassies: List<Poi>,
    missions: List<DiplomaticMission>,
    position: Pair<Double, Double>?,
    cities: List<String>,
    onOpenLink: (url: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = Spacing.l)) {
            InfoCardHeader(
                icon = AppIcons.Emergency,
                title = stringResource(R.string.emergency_title),
                modifier = Modifier.padding(horizontal = Spacing.l),
            )
            if (numbers == null) {
                CardDescription(
                    text = stringResource(R.string.emergency_no_central_number),
                    modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.s),
                )
            }
            numbers?.entries()?.forEach { (labelIds, number) ->
                val label = labelIds.map { stringResource(it) }.joinToString(" / ")
                val callLabel = stringResource(R.string.emergency_call, label, number)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(onClickLabel = callLabel) {
                            // Senza app telefono (tablet solo Wi-Fi) l'intent non ha destinatari: niente crash.
                            runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))) }
                        }
                        .padding(start = Spacing.l, end = Spacing.s, top = Spacing.s, bottom = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = label, style = MaterialTheme.typography.bodyMedium)
                        Text(text = number, style = MaterialTheme.typography.headlineSmall)
                    }
                    // Nel riquadro da 48dp dei pulsanti delle ambasciate: tutte le icone del riquadro in colonna.
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(imageVector = AppIcons.Call, contentDescription = null)
                    }
                }
            }
            embassiesCountry?.let { country -> EmbassiesSection(country, destination, embassies, missions, position, cities, onOpenLink) }
        }
    }
}

// Ambasciate e consolati del paese di chi viaggia nella regione (NationalityPreferences), dai POI OSM e da
// Wikidata fusi: la riga chiama se c'e' il telefono, sito ed email sono azioni a parte. Senza rappresentanze
// nei dati lo dice, invece di non mostrare nulla. In fondo il link agli avvisi di viaggio del ministero degli esteri del suo paese, o a quelli britannici se il suo paese non ne ha.
@Composable
private fun EmbassiesSection(
    country: String,
    destination: String?,
    embassies: List<Poi>,
    missions: List<DiplomaticMission>,
    position: Pair<Double, Double>?,
    cities: List<String>,
    onOpenLink: (url: String, title: String) -> Unit,
) {
    val language = LocalLocale.current.platformLocale.language
    val entries = remember(embassies, missions, cities, language) { mergeEmbassies(embassies, missions, cities, language) }
    val nearby = remember(entries, position) { position?.let { (lat, lon) -> nearbyEmbassies(entries, lat, lon) }.orEmpty() }
    HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.s), color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.2f))
    Text(
        text = stringResource(R.string.emergency_embassies_title, countryName(country)),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = Spacing.l).semantics { heading() },
    )
    if (entries.isEmpty()) {
        Text(
            text = stringResource(R.string.emergency_embassies_none),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.s),
        )
    }
    if (nearby.isEmpty()) {
        entries.forEach { entry -> EmbassyRow(entry, onOpenLink) }
    } else {
        // Le piu' vicine alla posizione del dispositivo, con la distanza, poi tutte le altre della nazione.
        EmbassiesSubheading(stringResource(R.string.emergency_embassies_nearby))
        nearby.forEach { (entry, km) -> EmbassyRow(entry, onOpenLink, distanceKm = km) }
        val others = entries - nearby.map { it.first }.toSet()
        if (others.isNotEmpty()) {
            EmbassiesSubheading(stringResource(R.string.emergency_embassies_others))
            others.forEach { entry -> EmbassyRow(entry, onOpenLink) }
        }
    }
    // Senza un servizio del proprio paese, quello britannico: meglio di nessun avviso, e la riga dice per chi e' scritto.
    val ownAdvice = travelAdviceSourceFor(country.uppercase())
    TravelAdviceRow(
        source = ownAdvice ?: fallbackTravelAdviceSource,
        destination = destination,
        description = stringResource(if (ownAdvice != null) R.string.emergency_travel_advice_description else R.string.emergency_travel_advice_fallback_description),
        onOpenLink = onOpenLink,
    )
}

@Composable
private fun EmbassiesSubheading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.m).semantics { heading() },
    )
}

@Composable
private fun EmbassyRow(entry: EmbassyEntry, onOpenLink: (url: String, title: String) -> Unit, distanceKm: Double? = null) {
    val context = LocalContext.current
    val phone = entry.phone
    val website = entry.website
    val email = entry.email
    // Chiamata, sito ed email come tre pulsanti uguali in fila, allineati alle icone delle altre righe.
    val url = website?.let(::webUrl)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = Spacing.l, end = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = Spacing.s)) {
            val kind = entry.kind?.let { stringResource(it.label()) }
            val distance = distanceKm?.let { distanceText(it) }
            listOfNotNull(kind, distance).takeIf { it.isNotEmpty() }?.let {
                Text(text = it.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
            }
            Text(text = entry.name, style = MaterialTheme.typography.bodyLarge)
            entry.address?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
        }
        if (phone != null) {
            IconButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null))) } }) {
                Icon(imageVector = AppIcons.Call, contentDescription = stringResource(R.string.emergency_call, entry.name, phone))
            }
        }
        if (url != null) {
            IconButton(onClick = { onOpenLink(url, entry.name) }) {
                Icon(imageVector = AppIcons.Web, contentDescription = stringResource(R.string.emergency_embassy_website, entry.name))
            }
        }
        if (email != null) {
            IconButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", email, null))) } }) {
                Icon(imageVector = AppIcons.Mail, contentDescription = stringResource(R.string.emergency_embassy_email, entry.name))
            }
        }
    }
}

// Il sito arriva da Wikidata, modificabile da chiunque: solo http(s), senza schema si assume https.
private fun webUrl(website: String): String? = safeWebUrl(website)

private const val METERS_PER_KM = 1000
private const val DISTANCE_DECIMALS_BELOW_KM = 10
// "800 m", "2,3 km", "45 km": decimali solo sotto i 10 km, nel formato della lingua.
private fun distanceText(km: Double): String = when {
    km < 1 -> "${(km * METERS_PER_KM).roundToInt()} m"
    km < DISTANCE_DECIMALS_BELOW_KM -> String.format(Locale.getDefault(), "%.1f km", km)
    else -> "${km.roundToInt()} km"
}

private fun MissionKind.label(): Int = when (this) {
    MissionKind.EMBASSY -> R.string.emergency_kind_embassy
    MissionKind.CONSULATE_GENERAL -> R.string.emergency_kind_consulate_general
    MissionKind.CONSULATE -> R.string.emergency_kind_consulate
}

// Solo il link: i contenuti dei siti dei ministeri (Viaggiare Sicuri e gli altri) non hanno una licenza aperta,
// quindi non si copiano nell'app; il nome del servizio resta quello ufficiale, nella sua lingua. Viaggiare Sicuri
// si apre sulla pagina del paese di destinazione.
@Composable
private fun TravelAdviceRow(source: OfficialSource, destination: String?, description: String, onOpenLink: (url: String, title: String) -> Unit) {
    val title = source.name
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = stringResource(R.string.emergency_travel_advice_open, title)) { onOpenLink(source.urlFor(destination), title) }
            .padding(start = Spacing.l, end = Spacing.s, top = Spacing.s, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
        }
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(imageVector = AppIcons.OpenExternal, contentDescription = null)
        }
    }
}

// Se il numero generale coincide con tutti gli altri (es. 911 negli Stati Uniti, 112 in
// Andorra) mostra una sola riga invece di quattro identiche; altrimenti raggruppa per numero
// (es. Giappone: ambulanza e vigili del fuoco condividono il 119) cosi' un numero condiviso
// non compare due volte.
private fun EmergencyNumbers.entries(): List<Pair<List<Int>, String>> {
    val generalNumber = general
    if (generalNumber != null && generalNumber == police && generalNumber == ambulance && generalNumber == fire) {
        return listOf(listOf(R.string.emergency_single) to generalNumber)
    }
    val labeled = buildList {
        general?.let { add(R.string.emergency_general to it) }
        add(R.string.emergency_police to police)
        add(R.string.emergency_ambulance to ambulance)
        add(R.string.emergency_fire to fire)
    }
    return labeled.groupBy({ it.second }, { it.first }).map { (number, labels) -> labels to number }
}
