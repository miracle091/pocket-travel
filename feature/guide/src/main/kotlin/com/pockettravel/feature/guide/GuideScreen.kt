package com.pockettravel.feature.guide

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.data.EmergencyNumbers
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.GuideSection
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.EmptyState
import com.pockettravel.core.ui.PocketTravelTheme
import com.pockettravel.core.ui.Spacing

@Composable
fun GuideScreen(
    regionId: String,
    onOpenSource: (url: String, title: String) -> Unit = { _, _ -> },
    viewModel: GuideViewModel = hiltViewModel(),
) {
    LaunchedEffect(regionId) { viewModel.load(regionId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    GuideContent(uiState = uiState, onOpenSource = onOpenSource)
}

@Composable
internal fun GuideContent(uiState: GuideUiState, onOpenSource: (url: String, title: String) -> Unit) {
    when {
        uiState.isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        uiState.loadError != null -> EmptyState(
            icon = AppIcons.Error,
            title = stringResource(uiState.loadError),
            modifier = Modifier.fillMaxSize(),
        )

        uiState.sections.isEmpty() && uiState.emergencyNumbers == null && !uiState.noCentralEmergencyNumber -> EmptyState(
            icon = AppIcons.Compass,
            title = stringResource(R.string.guide_empty_title),
            subtitle = stringResource(R.string.guide_empty_subtitle),
            modifier = Modifier.fillMaxSize(),
        )

        else -> GuideSectionsList(uiState, onOpenSource)
    }
}

@Composable
private fun GuideSectionsList(uiState: GuideUiState, onOpenSource: (url: String, title: String) -> Unit) {
    var selectedCategory by rememberSaveable { mutableStateOf<GuideCategory?>(null) }
    val categories = uiState.sections.map { it.category }.distinct()
    val visibleSections = uiState.sections.filter { selectedCategory == null || it.category == selectedCategory }

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
                                onClick = { selectedCategory = null },
                                label = { Text(stringResource(R.string.guide_filter_all)) },
                            )
                        }
                        items(categories) { category ->
                            FilterChip(
                                selected = category == selectedCategory,
                                onClick = { selectedCategory = if (category == selectedCategory) null else category },
                                label = { Text(stringResource(category.displayName())) },
                                leadingIcon = { Icon(category.icon(), contentDescription = null, modifier = Modifier.size(18.dp)) },
                            )
                        }
                    }
                }
            }
            if (selectedCategory == null && (uiState.emergencyNumbers != null || uiState.noCentralEmergencyNumber)) {
                item(key = "emergency_numbers") {
                    EmergencyNumbersCard(uiState.emergencyNumbers, modifier = Modifier.padding(horizontal = Spacing.l))
                }
            }
            items(visibleSections, key = { "${it.category}_${it.title}" }) { section ->
                GuideSectionCard(section, onOpenSource, modifier = Modifier.padding(horizontal = Spacing.l))
            }
        }
    }
}

// numbers nullo: la regione non ha un numero di emergenza centralizzato, e la scheda lo dichiara.
@Composable
private fun EmergencyNumbersCard(numbers: EmergencyNumbers?, modifier: Modifier = Modifier) {
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
                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
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

@Composable
private fun GuideSectionCard(
    section: GuideSection,
    onOpenSource: (url: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sourceTitle = stringResource(R.string.guide_source_title)
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.l, bottom = Spacing.xs)) {
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
            TextButton(
                onClick = { onOpenSource(section.sourceUrl, sourceTitle) },
                contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
                modifier = Modifier.padding(top = Spacing.xs),
            ) {
                Text(stringResource(R.string.guide_source))
                Spacer(modifier = Modifier.width(Spacing.s))
                Icon(AppIcons.OpenExternal, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// Le sottosezioni arrivano nel body come righe "▸ Titolo" (===Titolo=== di Wikivoyage, vedi
// cleanBody in GenerateGuideContent) oppure, nei pacchetti generati prima del fix, come ";Titolo"
// (lista di definizione wiki): entrambe mostrate come titolo, senza il simbolo davanti.
private val subheadingLineRegex = Regex("""^(?:▸|;)\s*(.+)$""")

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
