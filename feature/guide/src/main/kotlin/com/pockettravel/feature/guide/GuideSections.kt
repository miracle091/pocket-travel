package com.pockettravel.feature.guide

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pockettravel.core.data.CitySection
import com.pockettravel.core.data.GuideCategory
import com.pockettravel.core.data.GuideSection
import com.pockettravel.core.data.guideSourceUrlWithoutAudience
import com.pockettravel.core.ui.InfoCardDefaults
import com.pockettravel.core.ui.InfoCardHeader
import com.pockettravel.core.ui.R as UiR
import com.pockettravel.core.ui.Spacing

// Filtri per categoria + elenco delle sezioni: stessa vista sia per la guida della nazione sia per il
// dettaglio di una citta' (GuideSectionCard e' l'unico posto che disegna una sezione).
@Composable
internal fun SectionsWithFilters(
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
            val sourceUrls = visibleSections.map { guideSourceUrlWithoutAudience(it.sourceUrl) }.filter { it.isNotBlank() }.distinct()
            // Pagine da cui almeno una sezione mostrata e' tradotta automaticamente (CC BY-SA: modifica indicata).
            val translatedUrls = visibleSections.filter { it.translated }.map { guideSourceUrlWithoutAudience(it.sourceUrl) }.toSet()
            if (sourceUrls.isNotEmpty()) {
                item(key = "sources") {
                    GuideSourcesCard(sourceUrls, translatedUrls, onOpenSource, modifier = Modifier.padding(horizontal = Spacing.l))
                }
            }
        }
    }
}

// Forma comune a GuideSection e CitySection (stessa GuideCategory, titolo, corpo e fonte): un'unica
// riquadro/filtro per la guida del paese e per il dettaglio di una citta', senza dipendere da quale
// dei due repository ha prodotto la sezione.
internal data class SectionUi(
    val category: GuideCategory,
    val title: String,
    val body: String,
    val sourceUrl: String,
    val translated: Boolean = false,
)

internal fun GuideSection.toUi() = SectionUi(category, title, body, sourceUrl, translated)
internal fun CitySection.toUi() = SectionUi(category, title, body, sourceUrl, translated)

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
            // AI Act art. 50(4): un testo tradotto da un'IA e pubblicato su sicurezza, salute e leggi va
            // dichiarato su ogni contenuto, non solo nelle fonti in fondo, se nessuno lo ha rivisto.
            if (section.translated) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.guide_section_machine_translated),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(Spacing.m))
            GuideBody(section.body)
        }
    }
}

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
