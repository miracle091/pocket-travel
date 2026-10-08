package com.pockettravel.feature.guide

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.data.OfficialSource
import com.pockettravel.core.data.OfficialSourceTopic
import com.pockettravel.core.data.fallbackTravelAdviceSource
import com.pockettravel.core.data.officialSourcesRegistry
import com.pockettravel.core.data.travelAdviceSourceFor
import com.pockettravel.core.data.vaccination.AgeNote
import com.pockettravel.core.data.vaccination.PolioVaccine
import com.pockettravel.core.data.vaccination.PolioWindow
import com.pockettravel.core.data.vaccination.Vaccine
import com.pockettravel.core.data.vaccination.VaccinationItem
import com.pockettravel.core.data.vaccination.VaccinationLevel
import com.pockettravel.core.data.vaccination.VaccinationReason
import com.pockettravel.core.data.vaccination.VaccinationResult
import com.pockettravel.core.data.vaccination.vaccinationSourceName
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.CardDescription
import com.pockettravel.core.ui.CountryFlag
import com.pockettravel.core.ui.CountryPickerSheet
import com.pockettravel.core.ui.InfoCardDefaults
import com.pockettravel.core.ui.InfoCardHeader
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.countryName
import com.pockettravel.core.ui.R as UiR
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale


// Quale paese sta scegliendo il selettore: la partenza, un paese visitato prima o lo scalo con quell'id (>= 0).
private const val PICK_DEPARTURE = -1
private const val PICK_RECENT = -2

// Pagine dei servizi citati nelle voci, per le sigle usate nei dati (vaccinationSourceName).
private fun vaccinationSourceUrl(code: String): String? = when (code) {
    "F3" -> "https://www.who.int/groups/poliovirus-ihr-emergency-committee"
    "F6" -> "https://travel.gc.ca/travelling/health-safety/vaccines"
    "F7" -> "https://travelhealthpro.org.uk/countries"
    "F8" -> "https://www.gov.uk/foreign-travel-advice"
    "F9" -> "https://www.diplomatie.gouv.fr/fr/conseils-aux-voyageurs/"
    else -> null
}

private val travelHealthProSource = OfficialSource(
    name = "TravelHealthPro (UKHSA / NaTHNaC)",
    url = "https://travelhealthpro.org.uk/countries",
    description = R.string.vacc_link_travelhealthpro,
    topic = OfficialSourceTopic.HEALTH,
)

// Riquadro compatto della guida: l'esito per il percorso predefinito (nazionalita' o ultima partenza scelta
// verso il paese della regione). Mai "non serve": senza certificati dice che nei dati non risultano.
@Composable
internal fun VaccinationCard(state: VaccinationUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val locale = LocalLocale.current.platformLocale
    val result = state.result
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .clickable(onClickLabel = stringResource(R.string.vacc_card_open), onClick = onClick)
                .padding(Spacing.l),
        ) {
            InfoCardHeader(icon = AppIcons.Vaccinations, title = stringResource(R.string.vacc_title))
            // Senza partenza il riquadro mostra solo titolo e invito ad aprirla; con partenza uguale alla
            // destinazione lo dice, perche' altrimenti non si capirebbe l'assenza di risultati.
            if (result == null && state.departure != null) {
                CardDescription(
                    text = stringResource(R.string.vacc_choose_departure),
                    modifier = Modifier.padding(top = Spacing.s),
                )
            } else if (result != null && state.departure != null && state.destination != null) {
                Text(
                    text = stringResource(R.string.vacc_card_route, countryName(state.departure), countryName(state.destination)),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = Spacing.s),
                )
                val certificates = result.items.filter { it.isCertificate() }
                if (certificates.isEmpty()) {
                    CardDescription(
                        text = noCertificateText(result, locale),
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
                certificates.forEach { item ->
                    val line = stringResource(
                        if (item.level == VaccinationLevel.REQUIRED_EXIT) R.string.vacc_card_required_exit else R.string.vacc_card_required,
                        stringResource(item.vaccine.nameRes()),
                    )
                    Text(text = line, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.xs))
                }
            }
            CardDescription(
                text = stringResource(R.string.vacc_card_more),
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

// Azioni del contenuto, raccolte per separarlo dal ViewModel (come AiActions): i test le sostituiscono con lambda.
internal class VaccinationActions(
    val setDeparture: (String) -> Unit,
    val addRecentCountry: (String) -> Unit,
    val removeRecentCountry: (String) -> Unit,
    val addStopover: () -> Unit,
    val updateStopover: (id: Int, change: (StopoverInput) -> StopoverInput) -> Unit,
    val removeStopover: (Int) -> Unit,
    val setChildUnderOne: (Boolean) -> Unit,
    val setChildMonths: (Int?) -> Unit,
    val setStayOverFourWeeks: (Boolean) -> Unit,
    val setHajj: (Boolean) -> Unit,
)

// Schermata a tutto schermo sopra la scheda Guida, come l'elenco delle citta' (CitiesDialog): nessuna rotta
// del NavHost, il back chiude il dialogo.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VaccinationDialog(
    viewModel: VaccinationViewModel,
    onOpenSource: (url: String, title: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel) {
        VaccinationActions(
            setDeparture = viewModel::setDeparture,
            addRecentCountry = viewModel::addRecentCountry,
            removeRecentCountry = viewModel::removeRecentCountry,
            addStopover = viewModel::addStopover,
            updateStopover = viewModel::updateStopover,
            removeStopover = viewModel::removeStopover,
            setChildUnderOne = viewModel::setChildUnderOne,
            setChildMonths = viewModel::setChildMonths,
            setStayOverFourWeeks = viewModel::setStayOverFourWeeks,
            setHajj = viewModel::setHajj,
        )
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.vacc_screen_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(imageVector = AppIcons.Back, contentDescription = stringResource(UiR.string.back))
                        }
                    },
                )
            },
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
                VaccinationContent(state, actions, onOpenSource)
            }
        }
    }
}

@Composable
internal fun VaccinationContent(
    state: VaccinationUiState,
    actions: VaccinationActions,
    onOpenSource: (url: String, title: String) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf<Int?>(null) }
    val result = state.result
    LazyColumn(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
        contentPadding = PaddingValues(Spacing.l),
        verticalArrangement = Arrangement.spacedBy(InfoCardDefaults.CardGap),
    ) {
        // L'avviso viene prima di tutto, anche del percorso: e' la prima cosa che si legge.
        item(key = "disclaimer") { DisclaimerCard() }
        item(key = "route") { RouteCard(state, actions, onPick = { picking = it }) }
        if (result == null) {
            // senza partenza basta il "Scegli il paese" del percorso; con partenza uguale alla destinazione lo si dice
            if (state.departure != null) {
                item(key = "choose") {
                    Text(stringResource(R.string.vacc_choose_departure), style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            if (result.polioDataStale) {
                item(key = "stale") { StaleCard(result) }
            }
            resultGroups(result, state.nationality, onOpenSource)
        }
        item(key = "links") { LinksCard(state.nationality, onOpenSource) }
    }

    picking?.let { target ->
        val selected = when (target) {
            PICK_DEPARTURE -> state.departure
            PICK_RECENT -> null
            else -> state.stopovers.firstOrNull { it.id == target }?.country
        }
        CountryPickerSheet(
            title = stringResource(
                when (target) {
                    PICK_DEPARTURE -> R.string.vacc_from
                    PICK_RECENT -> R.string.vacc_add_country
                    else -> R.string.vacc_stopover_country
                },
            ),
            selected = selected,
            onSelect = { country ->
                when (target) {
                    PICK_DEPARTURE -> actions.setDeparture(country)
                    PICK_RECENT -> actions.addRecentCountry(country)
                    else -> actions.updateStopover(target) { it.copy(country = country) }
                }
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

// Tutti i campi del percorso in un riquadro: partenza e destinazione, scali, paesi visitati prima (chiuso di
// default) e le domande che cambiano il risultato.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteCard(state: VaccinationUiState, actions: VaccinationActions, onPick: (Int) -> Unit) {
    var previousExpanded by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = Spacing.s)) {
            CountryRow(
                label = stringResource(R.string.vacc_from),
                country = state.departure,
                onClick = { onPick(PICK_DEPARTURE) },
            )
            state.destination?.let { CountryRow(label = stringResource(R.string.vacc_to), country = it, onClick = null) }
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.s))

            SectionLabel(stringResource(R.string.vacc_stopovers))
            state.stopovers.forEach { stopover ->
                StopoverEditor(
                    stopover = stopover,
                    onPickCountry = { onPick(stopover.id) },
                    onChange = { change -> actions.updateStopover(stopover.id, change) },
                    onRemove = { actions.removeStopover(stopover.id) },
                )
            }
            TextButton(
                onClick = actions.addStopover,
                modifier = Modifier.padding(horizontal = Spacing.s),
            ) {
                Icon(AppIcons.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(Spacing.s))
                Text(stringResource(R.string.vacc_add_transit))
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.s))

            // Paesi visitati prima: chiuso di default, e il numero scelto si legge anche da chiuso.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(value = previousExpanded, role = Role.Button, onValueChange = { previousExpanded = it })
                    .padding(horizontal = Spacing.l, vertical = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val count = state.recentCountries.size
                    Text(
                        text = if (count > 0) "${stringResource(R.string.vacc_previous)} ($count)" else stringResource(R.string.vacc_previous),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(stringResource(R.string.vacc_previous_hint), style = MaterialTheme.typography.bodySmall)
                }
                Icon(
                    imageVector = AppIcons.ExpandMore,
                    contentDescription = stringResource(if (previousExpanded) R.string.vacc_previous_collapse else R.string.vacc_previous_expand),
                    modifier = Modifier.rotate(if (previousExpanded) 180f else 0f),
                )
            }
            AnimatedVisibility(visible = previousExpanded) {
                Column(modifier = Modifier.padding(horizontal = Spacing.l)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        state.recentCountries.forEach { code ->
                            val name = countryName(code)
                            InputChip(
                                selected = false,
                                onClick = { actions.removeRecentCountry(code) },
                                label = { Text(name) },
                                trailingIcon = {
                                    Icon(
                                        AppIcons.Close,
                                        contentDescription = stringResource(R.string.vacc_remove_country, name),
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                            )
                        }
                    }
                    TextButton(onClick = { onPick(PICK_RECENT) }) {
                        Icon(AppIcons.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(Spacing.s))
                        Text(stringResource(R.string.vacc_add_country))
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.s))

            SwitchRow(stringResource(R.string.vacc_child), state.childUnderOne, actions.setChildUnderOne)
            AnimatedVisibility(visible = state.childUnderOne) {
                ChildMonthsField(state.childMonths, actions.setChildMonths)
            }
            if (state.polioRelevant) {
                SwitchRow(stringResource(R.string.vacc_stay_4w), state.stayOverFourWeeks, actions.setStayOverFourWeeks)
            }
            if (state.destination.equals("SA", ignoreCase = true)) {
                SwitchRow(stringResource(R.string.vacc_hajj), state.hajj, actions.setHajj)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs).semantics { heading() },
    )
}

// Bandiera e nome del paese; con onClick il paese si cambia (matita), altrimenti e' solo informativo.
@Composable
private fun CountryRow(label: String, country: String?, onClick: (() -> Unit)?) {
    val change = stringResource(R.string.vacc_change_country)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = change, onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.l, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CountryFlag(country, size = 32.dp) { Icon(AppIcons.World, contentDescription = null) }
        Spacer(modifier = Modifier.width(Spacing.m))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = country?.let { countryName(it) } ?: stringResource(R.string.vacc_stopover_choose_country),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (onClick != null) Icon(AppIcons.Edit, contentDescription = null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StopoverEditor(
    stopover: StopoverInput,
    onPickCountry: () -> Unit,
    onChange: ((StopoverInput) -> StopoverInput) -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = Spacing.s)) {
            Box(modifier = Modifier.weight(1f)) {
                CountryRow(label = stringResource(R.string.vacc_stopover_country), country = stopover.country, onClick = onPickCountry)
            }
            IconButton(onClick = onRemove) {
                Icon(AppIcons.Delete, contentDescription = stringResource(R.string.vacc_remove_transit))
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier.padding(horizontal = Spacing.l),
        ) {
            OutlinedTextField(
                value = stopover.hours?.toString().orEmpty(),
                onValueChange = { text -> onChange { it.copy(hours = text.filter(Char::isDigit).take(3).toIntOrNull(), overTwelveHours = false) } },
                label = { Text(stringResource(R.string.vacc_stopover_hours)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                // larghezza minima, non fissa: con il testo ingrandito l'etichetta non si taglia
                modifier = Modifier.widthIn(min = 120.dp, max = 200.dp),
            )
            FilterChip(
                selected = stopover.overTwelveHours,
                onClick = { onChange { it.copy(hours = null, overTwelveHours = !it.overTwelveHours) } },
                label = { Text(stringResource(R.string.vacc_stopover_over_12h)) },
            )
            FilterChip(
                selected = stopover.leftAirport,
                onClick = { onChange { it.copy(leftAirport = !it.leftAirport) } },
                label = { Text(stringResource(R.string.vacc_left_airport)) },
            )
        }
    }
}

@Composable
private fun ChildMonthsField(months: Int?, onChange: (Int?) -> Unit) {
    // Il testo resta quello digitato anche se non e' valido (0-11): al motore va solo un valore valido.
    var text by rememberSaveable { mutableStateOf(months?.toString().orEmpty()) }
    val valid = text.isEmpty() || text.toIntOrNull()?.let { it in 0..11 } == true
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter(Char::isDigit).take(2)
            onChange(text.toIntOrNull()?.takeIf { it in 0..11 })
        },
        label = { Text(stringResource(R.string.vacc_child_months)) },
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs).widthIn(min = 150.dp, max = 250.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = Spacing.l, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(Spacing.m))
        Switch(checked = checked, onCheckedChange = null)
    }
}

// Avviso fisso in cima alla schermata, nei colori d'allerta del tema: non si chiude.
@Composable
private fun DisclaimerCard() {
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
private fun StaleCard(result: VaccinationResult) {
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
// sezione lo dice "nei nostri dati", con la data: mai "non serve".
private fun LazyListScope.resultGroups(
    result: VaccinationResult,
    nationality: String?,
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
    itemsIndexed(required, key = { index, _ -> "required_$index" }) { _, item -> VaccinationItemCard(item, nationality, onOpenSource) }
    if (recommended.isNotEmpty()) {
        item(key = "recommended_title") { GroupTitle(R.string.vacc_recommended) }
        itemsIndexed(recommended, key = { index, _ -> "recommended_$index" }) { _, item -> VaccinationItemCard(item, nationality, onOpenSource) }
    }
    if (consider.isNotEmpty()) {
        item(key = "consider_title") { GroupTitle(R.string.vacc_consider) }
        itemsIndexed(consider, key = { index, _ -> "consider_$index" }) { _, item -> VaccinationItemCard(item, nationality, onOpenSource) }
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
private fun VaccinationItemCard(item: VaccinationItem, nationality: String?, onOpenSource: (url: String, title: String) -> Unit) {
    val locale = LocalLocale.current.platformLocale
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(item.vaccine.nameRes()), style = MaterialTheme.typography.titleMedium)
            LevelBadge(item)
            item.reasonText()?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            item.detailTexts().forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            val note = if (locale.language == "en") item.noteEn.ifBlank { item.noteIt } else item.noteIt.ifBlank { item.noteEn }
            if (note.isNotBlank()) CardDescription(note.trim(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.reason == VaccinationReason.POLIO_ENTRY_UNLISTED) TravelAdviceLink(nationality, onOpenSource)
            if (item.sources.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.vacc_sources) + ":", style = MaterialTheme.typography.labelMedium)
                    FlowRow(modifier = Modifier.weight(1f)) {
                        item.sources.forEach { code ->
                            val name = vaccinationSourceName(code)
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

// Sito degli esteri della nazionalita' scelta al primo avvio (homepage: il registro non ha le pagine per paese),
// altrimenti GOV.UK, come nella Guida, con la descrizione che dice per chi e' scritto.
@Composable
private fun TravelAdviceLink(nationality: String?, onOpenSource: (url: String, title: String) -> Unit) {
    val own = travelAdviceSourceFor(nationality?.uppercase())
    val source = own ?: fallbackTravelAdviceSource
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = stringResource(R.string.vacc_open_source, source.name)) { onOpenSource(source.url, source.name) }
            .padding(vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = source.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(if (own != null) R.string.emergency_travel_advice_description else R.string.emergency_travel_advice_fallback_description),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Box(modifier = Modifier.heightIn(min = 48.dp).width(48.dp), contentAlignment = Alignment.Center) {
            Icon(imageVector = AppIcons.OpenExternal, contentDescription = null)
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

@Composable
private fun LinksCard(nationality: String?, onOpenSource: (url: String, title: String) -> Unit) {
    // OMS e CDC dal registro delle fonti ufficiali; Viaggiare Sicuri solo per chi ha nazionalita' italiana.
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
                            .clickable(onClickLabel = stringResource(R.string.vacc_open_source, source.name)) { onOpenSource(source.url, source.name) }
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

private fun VaccinationItem.isCertificate() = level == VaccinationLevel.REQUIRED || level == VaccinationLevel.REQUIRED_EXIT

@Composable
private fun noCertificateText(result: VaccinationResult, locale: Locale): String =
    result.checkedOn?.let { stringResource(R.string.vacc_none_found, formatDate(it, locale)) } ?: stringResource(R.string.vacc_none_found_no_date)

private fun formatDate(iso: String, locale: Locale): String =
    runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) }.getOrDefault(iso)

private fun Vaccine.nameRes(): Int = when (this) {
    Vaccine.YELLOW_FEVER -> R.string.vacc_vaccine_yellow_fever
    Vaccine.POLIO -> R.string.vacc_vaccine_polio
    Vaccine.MENACWY -> R.string.vacc_vaccine_menacwy
    Vaccine.HEPA -> R.string.vacc_vaccine_hepa
    Vaccine.HEPB -> R.string.vacc_vaccine_hepb
    Vaccine.TYPHOID -> R.string.vacc_vaccine_typhoid
    Vaccine.RABIES -> R.string.vacc_vaccine_rabies
    Vaccine.JE -> R.string.vacc_vaccine_je
    Vaccine.TBE -> R.string.vacc_vaccine_tbe
    Vaccine.CHOLERA -> R.string.vacc_vaccine_cholera
    Vaccine.DENGUE -> R.string.vacc_vaccine_dengue
    Vaccine.CHIK -> R.string.vacc_vaccine_chik
    Vaccine.ROUTINE -> R.string.vacc_vaccine_routine
}

// "Perché arrivi dal Kenya", "Per lo scalo di 14 ore in Etiopia"...: il motivo della voce nella lingua dell'interfaccia.
@Composable
private fun VaccinationItem.reasonText(): String? {
    val place = country?.let { countryName(it.uppercase()) }.orEmpty()
    val hours = transitHours
    return when (reason) {
        VaccinationReason.YF_ENTRY_ALL -> stringResource(R.string.vacc_reason_yf_all)
        VaccinationReason.YF_ENTRY_FROM_RISK, VaccinationReason.YF_ENTRY_FROM_LIST -> when {
            hours != null -> stringResource(R.string.vacc_reason_transit, hours, place)
            stopover -> stringResource(R.string.vacc_reason_transit_unknown, place)
            reason == VaccinationReason.YF_ENTRY_FROM_RISK -> stringResource(R.string.vacc_reason_from_risk, place)
            else -> stringResource(R.string.vacc_reason_from_list, place)
        }
        VaccinationReason.YF_EXIT -> stringResource(R.string.vacc_reason_yf_exit, place)
        VaccinationReason.YF_DESTINATION_RISK ->
            stringResource(if (partialArea) R.string.vacc_reason_yf_destination_partial else R.string.vacc_reason_yf_destination, place)
        VaccinationReason.POLIO_EXIT -> stringResource(R.string.vacc_reason_polio_exit, place)
        VaccinationReason.POLIO_ENCOURAGED -> stringResource(R.string.vacc_reason_polio_encouraged, place)
        VaccinationReason.POLIO_ENTRY -> stringResource(R.string.vacc_reason_polio_entry, place)
        VaccinationReason.POLIO_ENTRY_UNLISTED -> stringResource(R.string.vacc_reason_polio_entry_unlisted, place)
        VaccinationReason.HAJJ_UMRAH -> stringResource(R.string.vacc_reason_hajj)
        VaccinationReason.DESTINATION_MOST -> stringResource(R.string.vacc_reason_destination_most, place)
        VaccinationReason.DESTINATION_SOME -> stringResource(R.string.vacc_reason_destination_some, place)
        VaccinationReason.ROUTINE -> stringResource(R.string.vacc_reason_routine)
    }
}

// Dose, tempi, validita' ed eta' della voce, una riga ciascuno.
@Composable
private fun VaccinationItem.detailTexts(): List<String> = buildList {
    when (reason) {
        VaccinationReason.POLIO_EXIT -> add(stringResource(R.string.vacc_polio_exit))
        VaccinationReason.POLIO_ENCOURAGED -> add(stringResource(R.string.vacc_polio_encouraged))
        VaccinationReason.POLIO_ENTRY -> polioDose?.let { dose ->
            val vaccine = stringResource(if (dose.vaccine == PolioVaccine.IPV) R.string.vacc_polio_vaccine_ipv else R.string.vacc_polio_vaccine_bopv_or_ipv)
            add(stringResource(if (dose.window == PolioWindow.W4_12M) R.string.vacc_polio_dose_window else R.string.vacc_polio_dose_any, vaccine))
        }
        else -> Unit
    }
    minDaysBefore?.let { add(stringResource(R.string.vacc_days_before, it)) }
    validityYears?.let { add(stringResource(R.string.vacc_validity_years, it)) }
    val minAge = minAgeMonths
    if (minAge != null) {
        when {
            ageNote == AgeNote.BELOW_AGE -> add(stringResource(R.string.vacc_below_age, minAge))
            ageNote == AgeNote.FROM_AGE || reason == VaccinationReason.HAJJ_UMRAH -> add(stringResource(R.string.vacc_min_age, minAge))
        }
    }
}
