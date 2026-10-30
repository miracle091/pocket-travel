package com.pockettravel.feature.guide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.GuideSourceTarget
import com.pockettravel.core.data.MainCity
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.CardDescription
import com.pockettravel.core.ui.EmptyState
import com.pockettravel.core.ui.InfoCardHeader
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import com.pockettravel.core.ui.R as UiR
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.WeatherCard

private const val MILLION = 1_000_000L
private const val MILLION_DOUBLE = 1_000_000.0
private const val POPULATION_ROUND_FROM = 10_000L
private const val POPULATION_ROUND_STEP = 1_000L
private const val POPULATION_ROUND_HALF_STEP = 500L


// Riquadro delle guide delle citta': titolo con il numero di guide, le 5 citta' principali (per popolazione) in un
// breve elenco che apre subito la loro guida, e "Vedi tutte" (o il tocco sul riquadro) per l'elenco completo.
@Composable
internal fun CitiesEntryCard(cities: List<String>, mainCities: List<MainCity>, onOpen: (city: String?) -> Unit, modifier: Modifier = Modifier) {
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
internal fun CitiesDialog(
    regionId: String,
    cities: List<String>,
    initialCity: String?,
    viewModel: GuideViewModel,
    onOpenSource: (url: String, title: String) -> Unit,
    onDismiss: () -> Unit,
    // Sezione di una citta' citata da una risposta dell'assistente (vedi SectionsWithFilters).
    focus: GuideSourceTarget? = null,
) {
    var selectedCity by rememberSaveable { mutableStateOf(initialCity) }
    // Dopo un ripristino dello stato salvato la citta' scelta puo' essere un'altra da quella richiesta.
    LaunchedEffect(focus) { if (focus != null) selectedCity = initialCity }
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
                        city = city,
                        uiState = cityUiState,
                        onOpenSource = onOpenSource,
                        excludedTransit = excludedTransitCityNotes(city),
                        onRefreshWeather = viewModel::refreshCityWeather,
                        focus = focus?.takeIf { it.city == city },
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
    city: String,
    uiState: CityGuideUiState,
    onOpenSource: (url: String, title: String) -> Unit,
    excludedTransit: List<Int>,
    onRefreshWeather: () -> Unit,
    focus: GuideSourceTarget?,
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
            focus = focus,
            extraContent = if (excludedTransit.isEmpty() && uiState.weather == null && uiState.climate.isEmpty()) {
                null
            } else {
                {
                    uiState.weather?.let { weather ->
                        item(key = "weather") {
                            WeatherCard(weather, onOpenSource = onOpenSource, onRefresh = onRefreshWeather, modifier = Modifier.padding(horizontal = Spacing.l))
                        }
                    }
                    if (uiState.climate.isNotEmpty()) {
                        item(key = "climate") {
                            ClimateCard(city, uiState.climate, onOpenSource = onOpenSource, modifier = Modifier.padding(horizontal = Spacing.l))
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

// "2,8 milioni di abitanti" sopra il milione, altrimenti "360.000 abitanti" (arrotondato alle migliaia sopra i 10.000).
@Composable
private fun populationText(population: Long): String {
    val locale = LocalLocale.current.platformLocale
    return if (population >= MILLION) {
        val millions = java.text.NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(population / MILLION_DOUBLE)
        stringResource(R.string.guide_city_population_millions, millions)
    } else {
        val rounded = if (population >= POPULATION_ROUND_FROM) {
            (population + POPULATION_ROUND_HALF_STEP) / POPULATION_ROUND_STEP * POPULATION_ROUND_STEP
        } else {
            population
        }
        stringResource(R.string.guide_city_population, java.text.NumberFormat.getIntegerInstance(locale).format(rounded))
    }
}
