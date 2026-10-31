package com.pockettravel.feature.guide

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Card
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pockettravel.core.data.CityClimate
import com.pockettravel.core.ui.InfoCardHeader
import com.pockettravel.core.ui.R as UiR
import com.pockettravel.core.ui.Spacing
import java.text.NumberFormat
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle as MonthStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Una voce della riga di un mese: pioggia e giorni di pioggia si leggono insieme se ci sono entrambi. */
internal enum class ClimateFact { HIGH, LOW, RAIN_AND_DAYS, RAIN, DAYS }

/** [value] e' la temperatura o i millimetri; [days] solo per [ClimateFact.RAIN_AND_DAYS] e [ClimateFact.DAYS]. */
internal data class ClimateFactValue(val fact: ClimateFact, val value: Int, val days: Int = 0)

/** Colonne della tabella dei mesi. */
internal enum class ClimateColumn(@StringRes val label: Int) {
    HIGH(R.string.guide_climate_col_high),
    LOW(R.string.guide_climate_col_low),
    RAIN(R.string.guide_climate_col_rain),
    DAYS(R.string.guide_climate_col_days),
    ;

    fun valueOf(climate: CityClimate): Double? = when (this) {
        HIGH -> climate.tempMax
        LOW -> climate.tempMin
        RAIN -> climate.precipMm
        DAYS -> climate.rainDays
    }
}

/** Le voci presenti nei dati di un mese, arrotondate all'intero; quelle assenti non compaiono. */
internal fun climateFacts(climate: CityClimate): List<ClimateFactValue> = buildList {
    climate.tempMax?.let { add(ClimateFactValue(ClimateFact.HIGH, it.roundToInt())) }
    climate.tempMin?.let { add(ClimateFactValue(ClimateFact.LOW, it.roundToInt())) }
    val millimeters = climate.precipMm?.roundToInt()
    val days = climate.rainDays?.roundToInt()
    when {
        // Meno di mezzo giorno si arrotonda a 0: "in 0 giorni" non ha senso, resta la sola pioggia.
        millimeters != null && days != null && days > 0 -> add(ClimateFactValue(ClimateFact.RAIN_AND_DAYS, millimeters, days))
        millimeters != null -> add(ClimateFactValue(ClimateFact.RAIN, millimeters))
        days != null -> add(ClimateFactValue(ClimateFact.DAYS, days))
    }
}

/** Le colonne della tabella che hanno almeno un valore in un mese. */
internal fun climateColumns(climate: List<CityClimate>): List<ClimateColumn> =
    ClimateColumn.entries.filter { column -> climate.any { column.valueOf(it) != null } }

// Riquadro delle medie mensili (tabella climatica di Wikipedia, dal pacchetto: funziona senza rete): il mese in corso
// su una riga, "Tutti i mesi" apre la tabella dei dodici mesi. In fondo la fonte, apribile, accanto al pulsante.
@Composable
internal fun ClimateCard(
    city: String,
    climate: List<CityClimate>,
    onOpenSource: (url: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val currentMonth = remember { LocalDate.now().monthValue }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(top = Spacing.l, start = Spacing.l, end = Spacing.l, bottom = Spacing.xs)) {
            InfoCardHeader(
                icon = ImageVector.vectorResource(UiR.drawable.ms_partly_cloudy_day),
                title = stringResource(R.string.guide_climate_title),
            )
            val current = climate.firstOrNull { it.month == currentMonth }
            if (current != null && climateFacts(current).isNotEmpty()) {
                Text(
                    text = climateMonthText(current, locale, MonthStyle.FULL),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = Spacing.m),
                )
            }
            if (expanded) ClimateTable(climate, currentMonth, locale)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ClimateSource(city, climate, onOpenSource, Modifier.weight(1f))
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.guide_climate_hide else R.string.guide_climate_all_months))
                }
            }
        }
    }
}

@Composable
private fun ClimateTable(climate: List<CityClimate>, currentMonth: Int, locale: Locale) {
    val columns = climateColumns(climate)
    val numbers = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    Column(modifier = Modifier.padding(top = Spacing.s)) {
        // L'intestazione e' solo visiva: ogni riga si legge da sola, con i nomi delle voci.
        ClimateRow(
            label = "",
            cells = columns.map { stringResource(it.label) },
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.clearAndSetSemantics {},
        )
        climate.forEach { month ->
            val highlighted = month.month == currentMonth
            val description = climateMonthText(month, locale, MonthStyle.FULL)
            ClimateRow(
                label = monthName(month.month, locale, MonthStyle.SHORT),
                cells = columns.map { column -> column.valueOf(month)?.let { numbers.format(it.roundToInt()) } ?: "–" },
                style = MaterialTheme.typography.bodyMedium,
                highlighted = highlighted,
                modifier = Modifier.clearAndSetSemantics { contentDescription = description },
            )
        }
    }
}

@Composable
private fun ClimateRow(
    label: String,
    cells: List<String>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    // Il mese in corso su fondo evidenziato e in grassetto: non solo il colore lo distingue.
    Surface(
        color = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (highlighted) MaterialTheme.colorScheme.onSecondaryContainer else LocalContentColor.current,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs),
        ) {
            val weight = if (highlighted) FontWeight.Bold else null
            Text(text = label, style = style, fontWeight = weight, modifier = Modifier.weight(1f))
            cells.forEach { cell ->
                Text(text = cell, style = style, fontWeight = weight, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ClimateSource(
    city: String,
    climate: List<CityClimate>,
    onOpenSource: (url: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val url = climate.firstOrNull { it.sourceUrl.isNotBlank() }?.sourceUrl
    if (url == null) {
        Spacer(modifier)
        return
    }
    val title = stringResource(R.string.guide_source_title_wikipedia)
    Text(
        text = stringResource(R.string.guide_climate_source, city),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = stringResource(R.string.guide_climate_source_open)) { onOpenSource(url, title) }
            .wrapContentHeight(Alignment.CenterVertically),
    )
}

private fun monthName(month: Int, locale: Locale, style: MonthStyle): String =
    Month.of(month).getDisplayName(style, locale).replaceFirstChar { it.titlecase(locale) }

// "Ottobre: massima 22 °C, minima 13 °C, pioggia 113 mm in 8 giorni".
@Composable
private fun climateMonthText(climate: CityClimate, locale: Locale, style: MonthStyle): String {
    val facts = climateFacts(climate).map { climateFactText(it) }
    val month = monthName(climate.month, locale, style)
    return if (facts.isEmpty()) month else stringResource(R.string.guide_climate_month_line, month, facts.joinToString(", "))
}

@Composable
private fun climateFactText(fact: ClimateFactValue): String = when (fact.fact) {
    ClimateFact.HIGH -> stringResource(R.string.guide_climate_high, fact.value)
    ClimateFact.LOW -> stringResource(R.string.guide_climate_low, fact.value)
    ClimateFact.RAIN -> stringResource(R.string.guide_climate_rain, fact.value)
    ClimateFact.RAIN_AND_DAYS -> pluralStringResource(R.plurals.guide_climate_rain_and_days, fact.days, fact.value, fact.days)
    ClimateFact.DAYS -> pluralStringResource(R.plurals.guide_climate_days, fact.days, fact.days)
}
