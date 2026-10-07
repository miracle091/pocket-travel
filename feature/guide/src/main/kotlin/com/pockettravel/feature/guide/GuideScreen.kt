package com.pockettravel.feature.guide

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.data.CitySection
import com.pockettravel.core.data.DiplomaticMission
import com.pockettravel.core.data.EmbassyEntry
import com.pockettravel.core.data.EmergencyNumbers
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.GuideSourceSite
import com.pockettravel.core.data.guideSourceSiteOf
import com.pockettravel.core.data.GuideSection
import com.pockettravel.core.data.MainCity
import com.pockettravel.core.data.MissionKind
import com.pockettravel.core.data.OfficialSource
import com.pockettravel.core.data.Poi
import com.pockettravel.core.data.fallbackTravelAdviceSource
import com.pockettravel.core.data.mergeEmbassies
import com.pockettravel.core.data.nearbyEmbassies
import com.pockettravel.core.data.travelAdviceSourceFor
import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.CardDescription
import com.pockettravel.core.ui.EmptyState
import com.pockettravel.core.ui.InfoCardDefaults
import com.pockettravel.core.ui.InfoCardHeader
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import com.pockettravel.core.ui.PocketTravelTheme
import com.pockettravel.core.ui.R as UiR
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.countryName
import com.pockettravel.core.ui.safeWebUrl
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun GuideScreen(
    regionId: String,
    onOpenSource: (url: String, title: String) -> Unit = { _, _ -> },
    // Senza barra del titolo (hub della regione): Indietro sulla riga dei filtri.
    onBack: (() -> Unit)? = null,
    viewModel: GuideViewModel = hiltViewModel(),
    vaccinationViewModel: VaccinationViewModel = hiltViewModel(),
) {
    LaunchedEffect(regionId) { viewModel.load(regionId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCities by rememberSaveable(regionId) { mutableStateOf(false) }
    // citta' aperta direttamente da un chip del riquadro Citta' (null = si apre l'elenco)
    var initialCity by rememberSaveable(regionId) { mutableStateOf<String?>(null) }
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

        else -> GuideSectionsList(uiState, onOpenSource, onOpenCities, vaccination, onOpenVaccination, excludedTransit, onBack, onRefreshWeather)
    }
}

@Composable
private fun GuideBackButton(onBack: () -> Unit) {
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
        extraContent = {
            uiState.weather?.let { weather ->
                item(key = "weather") {
                    WeatherCard(weather, onOpenSource = onOpenSource, onRefresh = onRefreshWeather, modifier = Modifier.padding(horizontal = Spacing.l))
                }
            }
            if (uiState.emergencyNumbers != null || uiState.noCentralEmergencyNumber || uiState.embassiesCountry != null) {
                item(key = "emergency_numbers") {
                    EmergencyNumbersCard(
                        numbers = uiState.emergencyNumbers,
                        embassiesCountry = uiState.embassiesCountry,
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
private fun ExcludedTransitCard(notes: List<Int>, modifier: Modifier = Modifier) {
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

// Riquadro delle guide delle citta': titolo con il numero di guide, le 5 citta' principali (per popolazione) in un
// breve elenco che apre subito la loro guida, e "Vedi tutte" (o il tocco sul riquadro) per l'elenco completo.
@Composable
private fun CitiesEntryCard(cities: List<String>, mainCities: List<MainCity>, onOpen: (city: String?) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .clickable(onClickLabel = stringResource(R.string.guide_cities_all), onClick = { onOpen(null) })
                .padding(Spacing.l),
        ) {
            InfoCardHeader(
                icon = AppIcons.Cities,
                title = stringResource(R.string.guide_cities_title),
                trailing = {
                    Text(
                        text = pluralStringResource(R.plurals.guide_cities_count, cities.size, cities.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            CardDescription(
                text = stringResource(R.string.guide_cities_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            // Citta' principali (per abitanti) come riquadri separati da un filo di spazio, con gli angoli piu' tondi in
            // cima e in fondo al gruppo: nome (con una stella se e' la capitale), abitanti e freccia a destra, il segno di "apri".
            val openLabel = stringResource(R.string.guide_city_open)
            val shown = mainCities.ifEmpty { cities.take(MAIN_CITIES).map { MainCity(it, null) } }
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.m),
            ) {
                shown.forEachIndexed { index, city ->
                    val big = 16.dp
                    val small = 4.dp
                    val shape = RoundedCornerShape(
                        topStart = if (index == 0) big else small,
                        topEnd = if (index == 0) big else small,
                        bottomStart = if (index == shown.lastIndex) big else small,
                        bottomEnd = if (index == shown.lastIndex) big else small,
                    )
                    Surface(
                        onClick = { onOpen(city.name) },
                        shape = shape,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().semantics { onClick(label = openLabel, action = null) },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.heightIn(min = 64.dp).padding(horizontal = Spacing.m, vertical = Spacing.s),
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(start = Spacing.xs, end = Spacing.m)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = city.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    if (city.capital) {
                                        // stella accanto al nome della capitale, letta da TalkBack come "Capitale"
                                        Spacer(modifier = Modifier.width(Spacing.s))
                                        Icon(
                                            imageVector = AppIcons.Capital,
                                            contentDescription = stringResource(R.string.guide_city_capital),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                                city.population?.let { population ->
                                    Text(
                                        text = populationText(population),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Icon(imageVector = AppIcons.Open, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            if (cities.size > MAIN_CITIES) {
                TextButton(onClick = { onOpen(null) }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.guide_cities_all))
                }
            }
        }
    }
}


// Elenco delle citta' della regione (CityRepository.citiesFor) e dettaglio di una citta', in un
// dialogo a schermo intero sopra la scheda Guida — non una rotta separata del NavHost, come i
// documenti in PassportEditDialog: due sole viste "figlie" senza bisogno di un proprio back stack.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CitiesDialog(
    regionId: String,
    cities: List<String>,
    initialCity: String?,
    viewModel: GuideViewModel,
    onOpenSource: (url: String, title: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedCity by rememberSaveable { mutableStateOf(initialCity) }
    val goBack = {
        // Una citta' aperta da una scorciatoia del riquadro: indietro torna alla guida della nazione, non all'elenco.
        if (selectedCity != null && initialCity != null) {
            viewModel.clearCity()
            onDismiss()
        } else if (selectedCity != null) {
            viewModel.clearCity()
            selectedCity = null
        } else {
            onDismiss()
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false)) {
        // dismissOnBackPress = false: il back va prima dal dettaglio all'elenco, solo poi chiude il dialogo.
        // Dentro il Dialog, che ha il suo dispatcher del back: fuori non lo riceverebbe mai.
        BackHandler(onBack = goBack)
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(selectedCity ?: stringResource(R.string.guide_cities_title)) },
                    navigationIcon = {
                        IconButton(onClick = goBack) {
                            Icon(imageVector = AppIcons.Back, contentDescription = stringResource(UiR.string.back))
                        }
                    },
                )
            },
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                val city = selectedCity
                if (city == null) {
                    CityListContent(cities = cities, onCityClick = { selectedCity = it })
                } else {
                    LaunchedEffect(regionId, city) { viewModel.loadCity(regionId, city) }
                    val cityUiState by viewModel.cityUiState.collectAsStateWithLifecycle()
                    CityGuideContent(
                        uiState = cityUiState,
                        onOpenSource = onOpenSource,
                        excludedTransit = excludedTransitCityNotes(city),
                        onRefreshWeather = viewModel::refreshCityWeather,
                    )
                }
            }
        }
    }
}

@Composable
private fun CityListContent(cities: List<String>, onCityClick: (String) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(cities, key = { _, city -> city }) { index, city ->
            ListItem(
                leadingContent = { Icon(AppIcons.Cities, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().clickable { onCityClick(city) },
                content = { Text(city) },
            )
            if (index < cities.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
        }
    }
}

@Composable
private fun CityGuideContent(
    uiState: CityGuideUiState,
    onOpenSource: (url: String, title: String) -> Unit,
    excludedTransit: List<Int>,
    onRefreshWeather: () -> Unit,
) {
    var selectedCategory by rememberSaveable { mutableStateOf<GuideCategory?>(null) }
    when {
        uiState.isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PocketTravelLoadingIndicator()
        }

        uiState.loadError != null -> EmptyState(
            icon = AppIcons.Error,
            title = stringResource(uiState.loadError),
            modifier = Modifier.fillMaxSize(),
        )

        uiState.sections.isEmpty() -> EmptyState(
            icon = AppIcons.Cities,
            title = stringResource(R.string.guide_city_empty_title),
            modifier = Modifier.fillMaxSize(),
        )

        else -> SectionsWithFilters(
            sections = uiState.sections.map { it.toUi() },
            selectedCategory = selectedCategory,
            onSelectedCategoryChange = { selectedCategory = it },
            onOpenSource = onOpenSource,
            extraContent = if (excludedTransit.isEmpty() && uiState.weather == null) {
                null
            } else {
                {
                    uiState.weather?.let { weather ->
                        item(key = "weather") {
                            WeatherCard(weather, onOpenSource = onOpenSource, onRefresh = onRefreshWeather, modifier = Modifier.padding(horizontal = Spacing.l))
                        }
                    }
                    if (excludedTransit.isNotEmpty()) {
                        item(key = "excluded_transit") { ExcludedTransitCard(excludedTransit, modifier = Modifier.padding(horizontal = Spacing.l)) }
                    }
                }
            },
        )
    }
}

// Filtri per categoria + elenco delle sezioni: stessa vista sia per la guida della nazione sia per il
// dettaglio di una citta' (GuideSectionCard e' l'unico posto che disegna una sezione).
@Composable
private fun SectionsWithFilters(
    sections: List<SectionUi>,
    selectedCategory: GuideCategory?,
    onSelectedCategoryChange: (GuideCategory?) -> Unit,
    onOpenSource: (url: String, title: String) -> Unit,
    extraContent: (LazyListScope.() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val categories = sections.map { it.category }.distinct()
    val visibleSections = sections.filter { selectedCategory == null || it.category == selectedCategory }

    // Larghezza massima di lettura: sugli schermi larghi il testo della guida non si allunga su
    // righe da 200 caratteri.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
            contentPadding = PaddingValues(bottom = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(InfoCardDefaults.CardGap),
        ) {
            if (categories.size > 1 || onBack != null) {
                item(key = "filters") {
                    // Indietro a sinistra, fuori dallo scorrimento dei filtri.
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.s)) {
                    onBack?.let { Box(modifier = Modifier.padding(start = Spacing.xs)) { GuideBackButton(it) } }
                    if (categories.size > 1) LazyRow(
                        contentPadding = PaddingValues(start = if (onBack != null) Spacing.xs else Spacing.l, end = Spacing.l),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        modifier = Modifier.weight(1f),
                    ) {
                        item {
                            FilterChip(
                                selected = selectedCategory == null,
                                onClick = { onSelectedCategoryChange(null) },
                                label = { Text(stringResource(R.string.guide_filter_all)) },
                            )
                        }
                        items(categories) { category ->
                            FilterChip(
                                selected = category == selectedCategory,
                                onClick = { onSelectedCategoryChange(if (category == selectedCategory) null else category) },
                                label = { Text(stringResource(category.displayName())) },
                                leadingIcon = { Icon(category.icon(), contentDescription = null, modifier = Modifier.size(18.dp)) },
                            )
                        }
                    }
                    }
                }
            }
            if (selectedCategory == null && extraContent != null) {
                extraContent()
            }
            // L'indice nella chiave: categoria e titolo non sono garantiti unici, e una chiave doppia manda in crash la LazyColumn.
            itemsIndexed(visibleSections, key = { index, section -> "${section.category}_${section.title}_$index" }) { _, section ->
                GuideSectionCard(section, modifier = Modifier.padding(horizontal = Spacing.l))
            }
            // Le fonti una volta sola, in fondo, invece che sotto ogni riquadro (attribuzione CC BY-SA).
            val sourceUrls = visibleSections.map { it.sourceUrl }.filter { it.isNotBlank() }.distinct()
            if (sourceUrls.isNotEmpty()) {
                item(key = "sources") {
                    GuideSourcesCard(sourceUrls, onOpenSource, modifier = Modifier.padding(horizontal = Spacing.l))
                }
            }
        }
    }
}

// numbers nullo: la regione non ha un numero di emergenza centralizzato, e il riquadro lo dichiara.
@Composable
private fun EmergencyNumbersCard(
    numbers: EmergencyNumbers?,
    embassiesCountry: String?,
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
                            runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, "tel:$number".toUri())) }
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
            embassiesCountry?.let { country -> EmbassiesSection(country, embassies, missions, position, cities, onOpenLink) }
        }
    }
}

// Ambasciate e consolati del paese di chi viaggia nella regione (NationalityPreferences), dai POI OSM e da
// Wikidata fusi: la riga chiama se c'e' il telefono, sito ed email sono azioni a parte. Senza rappresentanze
// nei dati lo dice, invece di non mostrare nulla. In fondo il link agli avvisi di viaggio del ministero degli esteri del suo paese, o a quelli britannici se il suo paese non ne ha.
@Composable
private fun EmbassiesSection(
    country: String,
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
        // Le piu' vicine alla posizione del telefono, con la distanza, poi tutte le altre della nazione.
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
            IconButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, "tel:$phone".toUri())) } }) {
                Icon(imageVector = AppIcons.Call, contentDescription = stringResource(R.string.emergency_call, entry.name, phone))
            }
        }
        if (url != null) {
            IconButton(onClick = { onOpenLink(url, entry.name) }) {
                Icon(imageVector = AppIcons.Web, contentDescription = stringResource(R.string.emergency_embassy_website, entry.name))
            }
        }
        if (email != null) {
            IconButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, "mailto:$email".toUri())) } }) {
                Icon(imageVector = AppIcons.Mail, contentDescription = stringResource(R.string.emergency_embassy_email, entry.name))
            }
        }
    }
}

// Il sito arriva da Wikidata, modificabile da chiunque: solo http(s), senza schema si assume https.
private fun webUrl(website: String): String? = safeWebUrl(website)

// "800 m", "2,3 km", "45 km": decimali solo sotto i 10 km, nel formato della lingua.
private fun distanceText(km: Double): String = when {
    km < 1 -> "${(km * 1000).roundToInt()} m"
    km < 10 -> String.format(Locale.getDefault(), "%.1f km", km)
    else -> "${km.roundToInt()} km"
}

private fun MissionKind.label(): Int = when (this) {
    MissionKind.EMBASSY -> R.string.emergency_kind_embassy
    MissionKind.CONSULATE_GENERAL -> R.string.emergency_kind_consulate_general
    MissionKind.CONSULATE -> R.string.emergency_kind_consulate
}

// Solo il link: i contenuti dei siti dei ministeri (Viaggiare Sicuri e gli altri) non hanno una licenza aperta,
// quindi non si copiano nell'app; il nome del servizio resta quello ufficiale, nella sua lingua.
@Composable
private fun TravelAdviceRow(source: OfficialSource, description: String, onOpenLink: (url: String, title: String) -> Unit) {
    val title = source.name
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = stringResource(R.string.emergency_travel_advice_open, title)) { onOpenLink(source.url, title) }
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

// Forma comune a GuideSection e CitySection (stessa GuideCategory, titolo, corpo e fonte): un'unica
// riquadro/filtro per la guida del paese e per il dettaglio di una citta', senza dipendere da quale
// dei due repository ha prodotto la sezione.
internal data class SectionUi(val category: GuideCategory, val title: String, val body: String, val sourceUrl: String)

private fun GuideSection.toUi() = SectionUi(category, title, body, sourceUrl)
private fun CitySection.toUi() = SectionUi(category, title, body, sourceUrl)

@Composable
private fun GuideSectionCard(
    section: SectionUi,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        val (container, _) = section.category.tone()
        Column(modifier = Modifier.padding(Spacing.l)) {
            // Niente etichetta della categoria quando ripete il titolo ("Fatti rapidi").
            val category = stringResource(section.category.displayName())
            InfoCardHeader(
                icon = section.category.icon(),
                title = section.title,
                iconTint = container,
                overline = category.takeUnless { it.equals(section.title, ignoreCase = true) },
            )
            Spacer(modifier = Modifier.height(Spacing.m))
            GuideBody(section.body)
        }
    }
}

// Riquadro finale con le pagine Wikivoyage (e Wikipedia, per Storia e Clima delle citta'; travel.gc.ca per i consigli di
// viaggio del Governo del Canada) da cui vengono le sezioni mostrate: titolo della pagina (dall'URL, o il nome della
// fonte per i link senza /wiki/) e sito, ognuna apribile. La licenza sta nella schermata Licenze.
@Composable
private fun GuideSourcesCard(
    sourceUrls: List<String>,
    onOpenSource: (url: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val wikivoyageTitle = stringResource(R.string.guide_source_title)
    val wikipediaTitle = stringResource(R.string.guide_source_title_wikipedia)
    val travelAdviceTitle = stringResource(R.string.guide_source_title_travel_gc)
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
                }
                ListItem(
                    supportingContent = { Text(host) },
                    trailingContent = { Icon(AppIcons.OpenExternal, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onOpenSource(url, sourceTitle) },
                    content = { Text(if ("/wiki/" in url) sourcePageTitle(url) else sourceTitle) },
                )
            }
        }
    }
}

// "https://it.wikivoyage.org/wiki/San_Marino" -> "San Marino"; l'URL intero se non ha /wiki/.
internal fun sourcePageTitle(url: String): String =
    url.substringAfter("/wiki/", "").takeIf { it.isNotEmpty() }
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        ?.replace('_', ' ')
        ?: url

// Le sottosezioni arrivano nel body come righe "▸ Titolo" (===Titolo=== di Wikivoyage, vedi
// cleanBody in GenerateGuideContent) oppure, nei pacchetti meno recenti, come ";Titolo"
// (lista di definizione wiki): entrambe mostrate come titolo, senza il simbolo davanti.
private val subheadingLineRegex = Regex("""^[▸;]\s*(.+)$""")

internal data class GuideBodyBlock(val text: String, val isSubheading: Boolean)

internal fun guideBodyBlocks(body: String): List<GuideBodyBlock> {
    val blocks = mutableListOf<GuideBodyBlock>()
    val paragraph = mutableListOf<String>()
    fun flushParagraph() {
        val text = paragraph.joinToString("\n").trim('\n')
        if (text.isNotBlank()) blocks += GuideBodyBlock(text, isSubheading = false)
        paragraph.clear()
    }
    body.lineSequence().forEach { line ->
        val subheading = subheadingLineRegex.find(line.trim())?.groupValues?.get(1)
        if (subheading != null) {
            flushParagraph()
            blocks += GuideBodyBlock(subheading, isSubheading = true)
        } else {
            paragraph += line
        }
    }
    flushParagraph()
    return blocks
}

@Composable
private fun GuideBody(body: String) {
    guideBodyBlocks(body).forEach { block ->
        if (block.isSubheading) {
            Text(
                text = block.text,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = Spacing.l, bottom = Spacing.xs).semantics { heading() },
            )
        } else {
            Text(text = block.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@StringRes
private fun GuideCategory.displayName(): Int = when (this) {
    GuideCategory.USI_COSTUMI -> R.string.category_customs_usages
    GuideCategory.DOGANE -> R.string.category_customs
    GuideCategory.SALUTE -> R.string.category_health
    GuideCategory.SICUREZZA -> R.string.category_safety
    GuideCategory.TRASPORTI -> R.string.category_transport
    GuideCategory.FRASI_UTILI -> R.string.category_phrases
    GuideCategory.ALLOGGIO -> R.string.category_lodging
    GuideCategory.CIBO_BEVANDE -> R.string.category_food
    GuideCategory.ACQUISTI -> R.string.category_shopping
    GuideCategory.CONNETTIVITA -> R.string.category_connectivity
    GuideCategory.VITA_QUOTIDIANA -> R.string.category_daily_life
    GuideCategory.DA_SAPERE -> R.string.category_good_to_know
    GuideCategory.COSA_VEDERE -> R.string.category_see_do
    GuideCategory.FATTI_RAPIDI -> R.string.category_quick_facts
    GuideCategory.STORIA -> R.string.category_history
    GuideCategory.CLIMA -> R.string.category_climate
}

// Icona specifica per categoria, usata sia nei riquadri sia nei filtri.
@Composable
private fun GuideCategory.icon(): ImageVector = ImageVector.vectorResource(
    when (this) {
        GuideCategory.USI_COSTUMI -> UiR.drawable.ms_diversity_3
        GuideCategory.DOGANE -> UiR.drawable.ms_luggage
        GuideCategory.SALUTE -> UiR.drawable.ms_medical_services
        GuideCategory.SICUREZZA -> UiR.drawable.ms_shield
        GuideCategory.TRASPORTI -> UiR.drawable.ms_directions_bus
        GuideCategory.FRASI_UTILI -> UiR.drawable.ms_translate
        GuideCategory.ALLOGGIO -> UiR.drawable.ms_bed
        GuideCategory.CIBO_BEVANDE -> UiR.drawable.ms_restaurant
        GuideCategory.ACQUISTI -> UiR.drawable.ms_shopping_bag
        GuideCategory.CONNETTIVITA -> UiR.drawable.ms_wifi
        GuideCategory.VITA_QUOTIDIANA -> UiR.drawable.ms_home
        GuideCategory.DA_SAPERE -> UiR.drawable.ms_lightbulb
        GuideCategory.COSA_VEDERE -> UiR.drawable.ms_attractions
        GuideCategory.FATTI_RAPIDI -> UiR.drawable.ms_bolt
        GuideCategory.STORIA -> UiR.drawable.ms_history_edu
        GuideCategory.CLIMA -> UiR.drawable.ms_partly_cloudy_day
    },
)

// Tre toni dello schema (validi anche con i colori dinamici, in chiaro e scuro) per gruppi di
// significato: logistica in primary, vita locale e cultura in secondary, attenzione in tertiary.
// Colori pieni e non i container: con alcuni temi i tre container chiari sembrano uguali. Le coppie
// colore/onColore garantiscono il contrasto AA; il rosso resta al riquadro emergenze.
@Composable
private fun GuideCategory.tone(): Pair<Color, Color> {
    val colors = MaterialTheme.colorScheme
    return when (this) {
        GuideCategory.FATTI_RAPIDI, GuideCategory.TRASPORTI, GuideCategory.ALLOGGIO, GuideCategory.CONNETTIVITA,
        GuideCategory.CLIMA,
        -> colors.primary to colors.onPrimary

        GuideCategory.USI_COSTUMI, GuideCategory.FRASI_UTILI, GuideCategory.VITA_QUOTIDIANA,
        GuideCategory.CIBO_BEVANDE, GuideCategory.ACQUISTI, GuideCategory.COSA_VEDERE, GuideCategory.STORIA,
        -> colors.secondary to colors.onSecondary

        GuideCategory.SALUTE, GuideCategory.SICUREZZA, GuideCategory.DOGANE, GuideCategory.DA_SAPERE ->
            colors.tertiary to colors.onTertiary
    }
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

// "2,8 milioni di abitanti" sopra il milione, altrimenti "360.000 abitanti" (arrotondato alle migliaia sopra i 10.000).
@Composable
private fun populationText(population: Long): String {
    val locale = LocalLocale.current.platformLocale
    return if (population >= 1_000_000) {
        val millions = java.text.NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(population / 1_000_000.0)
        stringResource(R.string.guide_city_population_millions, millions)
    } else {
        val rounded = if (population >= 10_000) (population + 500) / 1_000 * 1_000 else population
        stringResource(R.string.guide_city_population, java.text.NumberFormat.getIntegerInstance(locale).format(rounded))
    }
}
