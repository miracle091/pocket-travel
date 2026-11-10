package com.pockettravel.feature.guide

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.data.vaccination.VaccinationLevel
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.CardDescription
import com.pockettravel.core.ui.CountryPickerSheet
import com.pockettravel.core.ui.InfoCardDefaults
import com.pockettravel.core.ui.InfoCardHeader
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.countryName
import com.pockettravel.core.ui.R as UiR

// Quale paese sta scegliendo il selettore: la partenza, un paese visitato prima o lo scalo con quell'id (>= 0).
internal const val PICK_DEPARTURE = -1
internal const val PICK_RECENT = -2

// Freccia girata quando la sezione e' aperta.
internal const val HALF_TURN_DEGREES = 180f
internal const val STOPOVER_HOURS_MAX_DIGITS = 3
internal val CHILD_MONTHS_RANGE = 0..11

// Riquadro compatto della guida: l'esito per il viaggio predefinito (nazionalita' o ultima partenza scelta
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
        // L'avviso viene prima di tutto, anche del viaggio: e' la prima cosa che si legge.
        item(key = "disclaimer") { DisclaimerCard() }
        item(key = "route") { RouteCard(state, actions, onPick = { picking = it }) }
        if (result == null) {
            // senza partenza basta il "Scegli il paese" del viaggio; con partenza uguale alla destinazione lo si dice
            if (state.departure != null) {
                item(key = "choose") {
                    Text(stringResource(R.string.vacc_choose_departure), style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            if (result.polioDataStale) {
                item(key = "stale") { StaleCard(result) }
            }
            resultGroups(result, state.nationality, state.destination, onOpenSource)
        }
        item(key = "links") { LinksCard(state.nationality, state.destination, onOpenSource) }
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
