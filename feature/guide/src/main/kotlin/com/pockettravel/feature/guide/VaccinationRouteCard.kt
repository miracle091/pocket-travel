package com.pockettravel.feature.guide

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.CountryFlag
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.countryName

// Tutti i campi del viaggio in un riquadro: partenza e destinazione, scali, paesi visitati prima (chiuso di
// default) e le domande che cambiano il risultato.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RouteCard(state: VaccinationUiState, actions: VaccinationActions, onPick: (Int) -> Unit) {
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
                Text(stringResource(R.string.vacc_add_stopover))
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
                    modifier = Modifier.rotate(if (previousExpanded) HALF_TURN_DEGREES else 0f),
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
                Icon(AppIcons.Delete, contentDescription = stringResource(R.string.vacc_remove_stopover))
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier.padding(horizontal = Spacing.l),
        ) {
            OutlinedTextField(
                value = stopover.hours?.toString().orEmpty(),
                onValueChange = { text -> onChange { it.copy(hours = text.filter(Char::isDigit).take(STOPOVER_HOURS_MAX_DIGITS).toIntOrNull(), overTwelveHours = false) } },
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
    val valid = text.isEmpty() || text.toIntOrNull()?.let { it in CHILD_MONTHS_RANGE } == true
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter(Char::isDigit).take(2)
            onChange(text.toIntOrNull()?.takeIf { it in CHILD_MONTHS_RANGE })
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
