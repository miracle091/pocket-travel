package com.pockettravel.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** Come sopra, nella lingua dell'interfaccia. */
@Composable
fun countryName(countryCode: String): String = countryName(countryCode, LocalLocale.current.platformLocale)

/** Nome del paese ISO 3166-1 alpha-2 in [locale] ("IT" -> "Italia"/"Italy"); il codice se sconosciuto. */
fun countryName(countryCode: String, locale: Locale): String =
    runCatching { Locale.Builder().setRegion(countryCode).build().getDisplayCountry(locale) }
        .getOrNull()?.takeIf { it.isNotBlank() && it != countryCode } ?: countryCode

// Tutti i paesi ISO col nome e in ordine alfabetico nella lingua dell'interfaccia (per lingua: si puo' cambiarla
// senza riavviare l'app).
private fun countries(locale: Locale): List<Pair<String, String>> {
    val collator = Collator.getInstance(locale)
    return Locale.getISOCountries().map { it to countryName(it, locale) }.sortedWith { a, b -> collator.compare(a.second, b.second) }
}

// Per cercare "cote" e trovare "Côte d'Ivoire".
private fun String.folded(): String = Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)

/** Foglio per scegliere un paese: ricerca per nome, bandiera, quello scelto con la spunta. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountryPickerSheet(title: String, selected: String?, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val locale = LocalLocale.current.platformLocale
    val all = remember(locale) { countries(locale) }
    val filtered = remember(all, query) { all.filter { (_, name) -> name.folded().contains(query.trim().folded()) } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Spacing.xl).semantics { heading() },
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.country_search_hint)) },
                leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
            )
            LazyColumn {
                items(filtered, key = { it.first }) { (code, name) ->
                    ListItem(
                        leadingContent = { CountryFlag(code, size = 32.dp) { Icon(AppIcons.World, contentDescription = null) } },
                        trailingContent = if (code == selected) {
                            { Icon(AppIcons.Check, contentDescription = stringResource(R.string.country_selected)) }
                        } else {
                            null
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onSelect(code) },
                        content = { Text(name) },
                    )
                }
            }
        }
    }
}
