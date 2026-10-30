package com.pockettravel.feature.guide

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.data.EmergencyNumbers
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.GuideSection
import com.pockettravel.core.data.GuideSourceTarget
import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.core.ui.AlertsCard
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.CardDescription
import com.pockettravel.core.ui.EmptyState
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import com.pockettravel.core.ui.PocketTravelTheme
import com.pockettravel.core.ui.R as UiR
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.WeatherCard

@Composable
fun GuideScreen(
    regionId: String,
    onOpenSource: (url: String, title: String) -> Unit = { _, _ -> },
    // Nazione della regione nel catalogo, per l'anteprima di una regione non scaricata (vedi GuideViewModel.load).
    catalogCountry: String? = null,
    // Senza barra del titolo (hub della regione): Indietro sulla riga dei filtri.
    onBack: (() -> Unit)? = null,
    // Sezione citata da una risposta dell'assistente: la guida la apre e ne evidenzia le frasi; poi chiama onTargetConsumed.
    target: GuideSourceTarget? = null,
    onTargetConsumed: () -> Unit = {},
    viewModel: GuideViewModel = hiltViewModel(),
    vaccinationViewModel: VaccinationViewModel = hiltViewModel(),
) {
    LaunchedEffect(regionId, catalogCountry) { viewModel.load(regionId, catalogCountry) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCities by rememberSaveable(regionId) { mutableStateOf(false) }
    // citta' aperta direttamente da un chip del riquadro Citta' (null = si apre l'elenco)
    var initialCity by rememberSaveable(regionId) { mutableStateOf<String?>(null) }
    // La sezione da mostrare resta qui finche' la schermata c'e': chi sta sopra puo' gia' avere dimenticato la richiesta.
    var focus by remember { mutableStateOf<GuideSourceTarget?>(null) }
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        focus = target
        if (target.city != null) {
            initialCity = target.city
            showCities = true
        }
        onTargetConsumed()
    }
    LaunchedEffect(regionId) { vaccinationViewModel.load(regionId) }
    val vaccinationState by vaccinationViewModel.uiState.collectAsStateWithLifecycle()
    var showVaccinations by rememberSaveable(regionId) { mutableStateOf(false) }

    GuideContent(
        uiState = uiState,
        onOpenSource = onOpenSource,
        onOpenCities = { city ->
            initialCity = city
            showCities = true
        },
        vaccination = vaccinationState,
        onOpenVaccination = { showVaccinations = true },
        excludedTransit = excludedTransitNotes(regionId),
        onBack = onBack,
        onRefreshWeather = viewModel::refreshWeather,
        focus = focus?.takeIf { it.city == null },
    )

    if (showVaccinations && vaccinationState.available) {
        VaccinationDialog(viewModel = vaccinationViewModel, onOpenSource = onOpenSource, onDismiss = { showVaccinations = false })
    }

    if (showCities) {
        CitiesDialog(
            regionId = regionId,
            cities = uiState.cities,
            initialCity = initialCity,
            viewModel = viewModel,
            onOpenSource = onOpenSource,
            onDismiss = { showCities = false },
            focus = focus?.takeIf { it.city != null },
        )
    }
}

@Composable
internal fun GuideContent(
    uiState: GuideUiState,
    onOpenSource: (url: String, title: String) -> Unit,
    onOpenCities: (city: String?) -> Unit = {},
    vaccination: VaccinationUiState = VaccinationUiState(),
    onOpenVaccination: () -> Unit = {},
    excludedTransit: List<Int> = emptyList(),
    onBack: (() -> Unit)? = null,
    onRefreshWeather: () -> Unit = {},
    focus: GuideSourceTarget? = null,
) {
    when {
        uiState.isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PocketTravelLoadingIndicator()
        }

        uiState.loadError != null -> Column {
            onBack?.let { GuideBackButton(it) }
            EmptyState(
                icon = AppIcons.Error,
                title = stringResource(uiState.loadError),
                modifier = Modifier.fillMaxSize(),
            )
        }

        uiState.sections.isEmpty() && uiState.emergencyNumbers == null && !uiState.noCentralEmergencyNumber && uiState.cities.isEmpty() -> Column {
            onBack?.let { GuideBackButton(it) }
            EmptyState(
                icon = AppIcons.Compass,
                title = stringResource(R.string.guide_empty_title),
                subtitle = stringResource(R.string.guide_empty_subtitle),
                modifier = Modifier.fillMaxSize(),
            )
        }

        else -> GuideSectionsList(uiState, onOpenSource, onOpenCities, vaccination, onOpenVaccination, excludedTransit, onBack, onRefreshWeather, focus)
    }
}

@Composable
internal fun GuideBackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(imageVector = AppIcons.Back, contentDescription = stringResource(UiR.string.back))
    }
}

// FATTI_RAPIDI (lingua, elettricita', fuso orario, valuta, numeri di emergenza) in cima alla
// guida non filtrata: sortedBy e' stabile, le altre sezioni restano nel loro ordine.
@Composable
private fun GuideSectionsList(
    uiState: GuideUiState,
    onOpenSource: (url: String, title: String) -> Unit,
    onOpenCities: (city: String?) -> Unit,
    vaccination: VaccinationUiState,
    onOpenVaccination: () -> Unit,
    excludedTransit: List<Int>,
    onBack: (() -> Unit)?,
    onRefreshWeather: () -> Unit,
    focus: GuideSourceTarget?,
) {
    var selectedCategory by rememberSaveable { mutableStateOf<GuideCategory?>(null) }
    val transport = transportSummary(uiState.transportCounts)
    val uiLanguage = LocalLocale.current.platformLocale.language
    val orderedSections = remember(uiState.sections, uiState.countryCode, transport, uiLanguage) {
        uiState.sections
            .sortedBy { if (it.category == GuideCategory.FATTI_RAPIDI) 0 else 1 }
            .map { section ->
                if (section.category != GuideCategory.FATTI_RAPIDI) return@map section
                // Etichette e valori aggiunti nella lingua delle guide installate (italiane o inglesi).
                val labels = QuickFactsLabels.of(section.body, uiLanguage)
                val extra = QuickFactsExtra(
                    language = uiState.countryCode?.let { languageOf(it, labels.locale) },
                    currency = uiState.countryCode?.let { currencyOf(it, labels.locale) },
                    transport = transport,
                )
                section.copy(body = quickFactsBody(section.body, extra, labels))
            }
    }
    SectionsWithFilters(
        sections = orderedSections.map { it.toUi() },
        selectedCategory = selectedCategory,
        onSelectedCategoryChange = { selectedCategory = it },
        onOpenSource = onOpenSource,
        onBack = onBack,
        focus = focus,
        extraContent = {
            uiState.weather?.let { weather ->
                item(key = "weather") {
                    WeatherCard(weather, onOpenSource = onOpenSource, onRefresh = onRefreshWeather, modifier = Modifier.padding(horizontal = Spacing.l))
                }
            }
            uiState.alerts?.takeIf { it.alerts.isNotEmpty() }?.let { alerts ->
                item(key = "alerts") {
                    AlertsCard(alerts, onOpenSource = onOpenSource, modifier = Modifier.padding(horizontal = Spacing.l))
                }
            }
            uiState.travelAdviceUpdate?.let { update ->
                item(key = "travel_advice_update") {
                    TravelAdviceNotice(update, onOpenSource = onOpenSource, modifier = Modifier.padding(horizontal = Spacing.l))
                }
            }
            if (uiState.emergencyNumbers != null || uiState.noCentralEmergencyNumber || uiState.embassiesCountry != null) {
                item(key = "emergency_numbers") {
                    EmergencyNumbersCard(
                        numbers = uiState.emergencyNumbers,
                        embassiesCountry = uiState.embassiesCountry,
                        destination = uiState.countryCode,
                        embassies = uiState.embassies,
                        missions = uiState.missions,
                        position = uiState.position,
                        cities = uiState.cities,
                        onOpenLink = onOpenSource,
                        modifier = Modifier.padding(horizontal = Spacing.l),
                    )
                }
            }
            // Senza dati vaccinali (pacchetto guide vecchio) il riquadro non compare.
            if (vaccination.available) {
                item(key = "vaccinations") {
                    VaccinationCard(vaccination, onClick = onOpenVaccination, modifier = Modifier.padding(horizontal = Spacing.l))
                }
            }
            if (uiState.cities.isNotEmpty()) {
                item(key = "cities") {
                    CitiesEntryCard(uiState.cities, uiState.mainCities, onOpen = onOpenCities, modifier = Modifier.padding(horizontal = Spacing.l))
                }
            }
            if (excludedTransit.isNotEmpty()) {
                item(key = "excluded_transit") {
                    ExcludedTransitCard(excludedTransit, modifier = Modifier.padding(horizontal = Spacing.l))
                }
            }
        },
    )
}

// Reti senza orari per la licenza (excludedTransitNotes): detto chiaramente, non lasciato al silenzio.
@Composable
internal fun ExcludedTransitCard(notes: List<Int>, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.l)) {
            Text(
                stringResource(R.string.transit_excluded_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            notes.forEach { note ->
                CardDescription(stringResource(note), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.s))
            }
        }
    }
}

// "treno (12 stazioni), autobus (3 autostazioni)": i mezzi con piu' punti di partenza per primi.
@Composable
private fun transportSummary(counts: Map<PoiCategory, Int>): String? =
    counts.entries.sortedByDescending { it.value }.mapNotNull { (category, count) ->
        val plural = when (category) {
            PoiCategory.TRENO -> R.plurals.quick_facts_train
            PoiCategory.METRO -> R.plurals.quick_facts_metro
            PoiCategory.AUTOBUS -> R.plurals.quick_facts_bus
            PoiCategory.TRAGHETTO -> R.plurals.quick_facts_ferry
            PoiCategory.AEROPORTO -> R.plurals.quick_facts_airport
            else -> return@mapNotNull null
        }
        pluralStringResource(plural, count, count)
    }.let { modes ->
        // breve elenco puntato dal mezzo piu' diffuso; un solo mezzo resta sulla riga dell'etichetta
        modes.singleOrNull() ?: modes.joinToString("\n") { "• $it" }.ifEmpty { null }
    }

@Preview(widthDp = 360, heightDp = 800)
@Composable
private fun GuidePreview() {
    PocketTravelTheme(dynamicColor = false) {
        Surface {
            GuideContent(
                uiState = GuideUiState(
                    isLoading = false,
                    emergencyNumbers = EmergencyNumbers(general = "112", police = "113", ambulance = "118", fire = "115"),
                    cities = listOf("Serravalle", "Borgo Maggiore"),
                    sections = listOf(
                        GuideSection(
                            regionId = "san-marino",
                            category = GuideCategory.CIBO_BEVANDE,
                            title = "A tavola",
                            body = "Dolci tipici sono la torta Titano e la zuppa di ciliegie.\n▸ Vini rossi\n• Sangiovese di San Marino",
                            sourceUrl = "https://it.wikivoyage.org/wiki/San_Marino",
                        ),
                    ),
                ),
                onOpenSource = { _, _ -> },
            )
        }
    }
}

@Preview(widthDp = 360, heightDp = 400)
@Composable
private fun GuideNoCentralEmergencyNumberPreview() {
    PocketTravelTheme(dynamicColor = false) {
        Surface {
            GuideContent(
                uiState = GuideUiState(isLoading = false, noCentralEmergencyNumber = true),
                onOpenSource = { _, _ -> },
            )
        }
    }
}
