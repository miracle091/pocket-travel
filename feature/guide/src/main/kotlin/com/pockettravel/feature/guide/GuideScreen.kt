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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.data.CitySection
import com.pockettravel.core.data.EmergencyNumbers
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.GuideSection
import com.pockettravel.core.data.Poi
import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.countryName
import com.pockettravel.core.ui.EmptyState
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import com.pockettravel.core.ui.PocketTravelTheme
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.R as UiR

@Composable
fun GuideScreen(
    regionId: String,
    onOpenSource: (url: String, title: String) -> Unit = { _, _ -> },
    viewModel: GuideViewModel = hiltViewModel(),
) {
    LaunchedEffect(regionId) { viewModel.load(regionId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCities by rememberSaveable(regionId) { mutableStateOf(false) }

    GuideContent(uiState = uiState, onOpenSource = onOpenSource, onOpenCities = { showCities = true })

    if (showCities) {
        CitiesDialog(
            regionId = regionId,
            cities = uiState.cities,
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
    onOpenCities: () -> Unit = {},
) {
    when {
        uiState.isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PocketTravelLoadingIndicator()
        }

        uiState.loadError != null -> EmptyState(
            icon = AppIcons.Error,
            title = stringResource(uiState.loadError),
            modifier = Modifier.fillMaxSize(),
        )

        uiState.sections.isEmpty() && uiState.emergencyNumbers == null && !uiState.noCentralEmergencyNumber && uiState.cities.isEmpty() -> EmptyState(
            icon = AppIcons.Compass,
            title = stringResource(R.string.guide_empty_title),
            subtitle = stringResource(R.string.guide_empty_subtitle),
            modifier = Modifier.fillMaxSize(),
        )

        else -> GuideSectionsList(uiState, onOpenSource, onOpenCities)
    }
}

// FATTI_RAPIDI (lingua, elettricita', fuso orario, valuta, numeri di emergenza) in cima alla
// guida non filtrata: sortedBy e' stabile, le altre sezioni restano nel loro ordine.
@Composable
private fun GuideSectionsList(uiState: GuideUiState, onOpenSource: (url: String, title: String) -> Unit, onOpenCities: () -> Unit) {
    var selectedCategory by rememberSaveable { mutableStateOf<GuideCategory?>(null) }
    val transport = transportSummary(uiState.transportCounts)
    val orderedSections = remember(uiState.sections, uiState.countryCode, transport) {
        val extra = QuickFactsExtra(
            language = uiState.countryCode?.let(::languageOf),
            currency = uiState.countryCode?.let(::currencyOf),
            transport = transport,
        )
        uiState.sections
            .sortedBy { if (it.category == GuideCategory.FATTI_RAPIDI) 0 else 1 }
            .map { if (it.category == GuideCategory.FATTI_RAPIDI) it.copy(body = quickFactsBody(it.body, extra)) else it }
    }
    SectionsWithFilters(
        sections = orderedSections.map { it.toUi() },
        selectedCategory = selectedCategory,
        onSelectedCategoryChange = { selectedCategory = it },
        onOpenSource = onOpenSource,
        extraContent = {
            if (uiState.cities.isNotEmpty()) {
                item(key = "cities") {
                    CitiesEntryCard(onClick = onOpenCities, modifier = Modifier.padding(horizontal = Spacing.l))
                }
            }
            if (uiState.emergencyNumbers != null || uiState.noCentralEmergencyNumber || uiState.embassiesCountry != null) {
                item(key = "emergency_numbers") {
                    EmergencyNumbersCard(
                        numbers = uiState.emergencyNumbers,
                        embassiesCountry = uiState.embassiesCountry,
                        embassies = uiState.embassies,
                        modifier = Modifier.padding(horizontal = Spacing.l),
                    )
                }
            }
        },
    )
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
    }.joinToString(", ").ifEmpty { null }

@Composable
private fun CitiesEntryCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        ListItem(
            supportingContent = { Text(stringResource(R.string.guide_cities_subtitle)) },
            leadingContent = {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(
                        AppIcons.Cities,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(6.dp).size(20.dp),
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(onClick = onClick),
            content = { Text(stringResource(R.string.guide_cities_title)) },
        )
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
    viewModel: GuideViewModel,
    onOpenSource: (url: String, title: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedCity by rememberSaveable { mutableStateOf<String?>(null) }
    val goBack = {
        if (selectedCity != null) {
            viewModel.clearCity()
            selectedCity = null
        } else {
            onDismiss()
        }
    }
    // dismissOnBackPress = false: il back va prima dal dettaglio all'elenco, solo poi chiude il dialogo.
    BackHandler(onBack = goBack)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false)) {
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
                    CityGuideContent(uiState = cityUiState, onOpenSource = onOpenSource)
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
private fun CityGuideContent(uiState: CityGuideUiState, onOpenSource: (url: String, title: String) -> Unit) {
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
        )
    }
}

// Filtri per categoria + elenco delle sezioni: stessa vista sia per la guida del paese sia per il
// dettaglio di una citta' (GuideSectionCard e' l'unico posto che disegna una sezione).
@Composable
private fun SectionsWithFilters(
    sections: List<SectionUi>,
    selectedCategory: GuideCategory?,
    onSelectedCategoryChange: (GuideCategory?) -> Unit,
    onOpenSource: (url: String, title: String) -> Unit,
    extraContent: (LazyListScope.() -> Unit)? = null,
) {
    val categories = sections.map { it.category }.distinct()
    val visibleSections = sections.filter { selectedCategory == null || it.category == selectedCategory }

    // Larghezza massima di lettura: sugli schermi larghi il testo della guida non si allunga su
    // righe da 200 caratteri.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
            contentPadding = PaddingValues(bottom = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            if (categories.size > 1) {
                item(key = "filters") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Spacing.l),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        modifier = Modifier.padding(top = Spacing.s),
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
            if (selectedCategory == null && extraContent != null) {
                extraContent()
            }
            items(visibleSections, key = { "${it.category}_${it.title}" }) { section ->
                GuideSectionCard(section, modifier = Modifier.padding(horizontal = Spacing.l))
            }
            // Le fonti una volta sola, in fondo, invece che sotto ogni scheda (attribuzione CC BY-SA).
            val sourceUrls = visibleSections.map { it.sourceUrl }.filter { it.isNotBlank() }.distinct()
            if (sourceUrls.isNotEmpty()) {
                item(key = "sources") {
                    GuideSourcesCard(sourceUrls, onOpenSource, modifier = Modifier.padding(horizontal = Spacing.l))
                }
            }
        }
    }
}

// numbers nullo: la regione non ha un numero di emergenza centralizzato, e la scheda lo dichiara.
@Composable
private fun EmergencyNumbersCard(numbers: EmergencyNumbers?, embassiesCountry: String?, embassies: List<Poi>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = Spacing.l)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = Spacing.l),
            ) {
                Icon(imageVector = AppIcons.Emergency, contentDescription = null)
                Spacer(modifier = Modifier.width(Spacing.s))
                Text(
                    text = stringResource(R.string.emergency_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            if (numbers == null) {
                Text(
                    text = stringResource(R.string.emergency_no_central_number),
                    style = MaterialTheme.typography.bodyMedium,
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
                            context.startActivity(Intent(Intent.ACTION_DIAL, "tel:$number".toUri()))
                        }
                        .padding(horizontal = Spacing.l, vertical = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = label, style = MaterialTheme.typography.bodyMedium)
                        Text(text = number, style = MaterialTheme.typography.headlineSmall)
                    }
                    Icon(imageVector = AppIcons.Call, contentDescription = null)
                }
            }
            embassiesCountry?.let { country -> EmbassiesSection(country, embassies) }
        }
    }
}

// Ambasciate e consolati del paese di chi viaggia nella regione (NationalityPreferences): la riga chiama
// se OSM ha il telefono. Senza rappresentanze nei dati lo dice, invece di non mostrare nulla.
@Composable
private fun EmbassiesSection(country: String, embassies: List<Poi>) {
    val context = LocalContext.current
    HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.s), color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.2f))
    Text(
        text = stringResource(R.string.emergency_embassies_title, countryName(country)),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = Spacing.l).semantics { heading() },
    )
    if (embassies.isEmpty()) {
        Text(
            text = stringResource(R.string.emergency_embassies_none),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.s),
        )
    }
    embassies.forEach { embassy ->
        val phone = embassy.phone
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(
                    if (phone != null) {
                        Modifier.clickable(onClickLabel = stringResource(R.string.emergency_call, embassy.name, phone)) {
                            context.startActivity(Intent(Intent.ACTION_DIAL, "tel:$phone".toUri()))
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = Spacing.l, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = embassy.name, style = MaterialTheme.typography.bodyMedium)
                phone?.let { Text(text = it, style = MaterialTheme.typography.titleMedium) }
                embassy.address?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
            }
            if (phone != null) Icon(imageVector = AppIcons.Call, contentDescription = null)
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
// scheda/filtro per la guida del paese e per il dettaglio di una citta', senza dipendere da quale
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
        Column(modifier = Modifier.padding(Spacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(
                        imageVector = section.category.icon(),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(6.dp).size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.s))
                Text(
                    text = stringResource(section.category.displayName()),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = Spacing.m, bottom = Spacing.s).semantics { heading() },
            )
            GuideBody(section.body)
        }
    }
}

// Scheda finale con le pagine Wikivoyage da cui vengono le sezioni mostrate: titolo della pagina
// (dall'URL) e sito, ognuna apribile. La licenza sta nella schermata Licenze.
@Composable
private fun GuideSourcesCard(
    sourceUrls: List<String>,
    onOpenSource: (url: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sourceTitle = stringResource(R.string.guide_source_title)
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = Spacing.l)) {
            Text(
                text = stringResource(R.string.guide_sources_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = Spacing.l).semantics { heading() },
            )
            sourceUrls.forEach { url ->
                ListItem(
                    supportingContent = { Text(url.toUri().host.orEmpty()) },
                    trailingContent = { Icon(AppIcons.OpenExternal, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onOpenSource(url, sourceTitle) },
                    content = { Text(sourcePageTitle(url)) },
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
// cleanBody in GenerateGuideContent) oppure, nei pacchetti generati prima del fix, come ";Titolo"
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
                style = MaterialTheme.typography.titleMedium,
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
}

// Nessuna icona del set copre esattamente "sicurezza"/"trasporti": usate le piu' vicine per
// significato (autorita' ufficiale per gli avvisi di sicurezza, voli come forma di trasporto).
@Composable
private fun GuideCategory.icon(): ImageVector = when (this) {
    GuideCategory.USI_COSTUMI -> AppIcons.Checklist
    GuideCategory.DOGANE -> AppIcons.Customs
    GuideCategory.SALUTE -> AppIcons.HealthGuidance
    GuideCategory.SICUREZZA -> AppIcons.OfficialAuthority
    GuideCategory.TRASPORTI -> AppIcons.Flights
    GuideCategory.FRASI_UTILI -> AppIcons.Translation
    GuideCategory.ALLOGGIO -> AppIcons.Accommodation
    GuideCategory.CIBO_BEVANDE -> AppIcons.FoodDrink
    GuideCategory.ACQUISTI -> AppIcons.Shopping
    GuideCategory.CONNETTIVITA -> AppIcons.Connectivity
    GuideCategory.VITA_QUOTIDIANA -> AppIcons.DailyLife
    GuideCategory.DA_SAPERE -> AppIcons.Info
    GuideCategory.COSA_VEDERE -> AppIcons.Attractions
    GuideCategory.FATTI_RAPIDI -> AppIcons.QuickFacts
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
