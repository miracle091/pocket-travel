package com.pockettravel.feature.map

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.pockettravel.core.poi.PoiCategory
import com.pockettravel.core.ui.Spacing

/** Gruppi della legenda: ogni categoria sta in uno e un solo gruppo (vedi MapLegendTest). */
internal enum class LegendGroup(@StringRes val label: Int, val categories: List<PoiCategory>) {
    EAT_SLEEP(R.string.map_legend_group_eat_sleep, listOf(PoiCategory.ALLOGGIO, PoiCategory.CIBO_BEVANDE)),
    VISIT(
        R.string.map_legend_group_visit,
        listOf(
            PoiCategory.LUOGHI_DI_CULTO, PoiCategory.MUSEI_ARTE, PoiCategory.LUOGHI_STORICI, PoiCategory.NATURA, PoiCategory.PANORAMI,
            PoiCategory.PARCHI_DIVERTIMENTO, PoiCategory.ZOO, PoiCategory.PARCHI_ACQUATICI, PoiCategory.ATTRAZIONI, PoiCategory.SVAGO, PoiCategory.SPORT, PoiCategory.PARCO_GIOCHI, PoiCategory.TAVOLI_PICNIC, PoiCategory.RIPARI),
    ),
    SHOPS(R.string.map_legend_group_shops, listOf(PoiCategory.NEGOZI, PoiCategory.DISTRIBUTORI)),
    MONEY_POST(
        R.string.map_legend_group_money_post,
        listOf(PoiCategory.BANCA, PoiCategory.BANCOMAT, PoiCategory.UFFICIO_POSTALE, PoiCategory.CASSETTA_POSTALE),
    ),
    USEFUL(
        R.string.map_legend_group_useful,
        listOf(PoiCategory.BAGNI_PUBBLICI, PoiCategory.ACQUA_POTABILE, PoiCategory.INFORMAZIONI, PoiCategory.BIBLIOTECHE, PoiCategory.AMBASCIATA_CONSOLATO),
    ),
    TRANSPORT(
        R.string.map_legend_group_transport,
        listOf(PoiCategory.TRENO, PoiCategory.METRO, PoiCategory.AUTOBUS, PoiCategory.TAXI, PoiCategory.TRAGHETTO, PoiCategory.AEROPORTO),
    ),
    VEHICLES(
        R.string.map_legend_group_vehicles,
        listOf(
            PoiCategory.CARBURANTE, PoiCategory.RICARICA, PoiCategory.SERVIZI_CAMPER, PoiCategory.RIPARAZIONE_BICI, PoiCategory.PARCHEGGIO,
            PoiCategory.PARCHEGGIO_PRIVATO, PoiCategory.NOLEGGIO,
        ),
    ),
    HEALTH(
        R.string.map_legend_group_health,
        listOf(
            PoiCategory.FARMACIA, PoiCategory.OSPEDALE, PoiCategory.AMBULATORI, PoiCategory.POLIZIA, PoiCategory.VIGILI_DEL_FUOCO,
            PoiCategory.MUNICIPIO, PoiCategory.VETERINARIO,
        ),
    ),
    OTHER(R.string.map_legend_group_other, listOf(PoiCategory.ALTRO)),
}

// Filtri della mappa: tutte le categorie presenti nella regione, a gruppi, ciascuna con il suo
// interruttore e ogni gruppo con "Mostra tutti"/"Nascondi tutti"; scelte salvate per tutte le regioni
// (MapFilterPreferences).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapLegendSheet(
    presentCategories: Set<PoiCategory>,
    hiddenCategories: Set<PoiCategory>,
    onHiddenCategoriesChange: (Set<PoiCategory>) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Spacing.l)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.map_legend),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (hiddenCategories.any { it in presentCategories }) {
                    TextButton(onClick = { onHiddenCategoriesChange(hiddenCategories - presentCategories) }) {
                        Text(stringResource(R.string.map_legend_show_all))
                    }
                }
            }
            LegendGroup.entries.forEach { group ->
                val categories = group.categories.filter { it in presentCategories }
                if (categories.isEmpty()) return@forEach
                val allVisible = categories.none { it in hiddenCategories }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = Spacing.xl, end = Spacing.m, top = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(group.label),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    TextButton(
                        onClick = {
                            onHiddenCategoriesChange(if (allVisible) hiddenCategories + categories else hiddenCategories - categories.toSet())
                        },
                    ) {
                        Text(stringResource(if (allVisible) R.string.map_filters_group_hide else R.string.map_filters_group_show))
                    }
                }
                categories.forEach { category ->
                    val visible = category !in hiddenCategories
                    ListItem(
                        leadingContent = { PoiBadge(category, size = 32) },
                        trailingContent = { Switch(checked = visible, onCheckedChange = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .toggleable(value = visible, role = Role.Switch) {
                                onHiddenCategoriesChange(if (visible) hiddenCategories + category else hiddenCategories - category)
                            }
                            .padding(horizontal = Spacing.s),
                        content = { Text(stringResource(category.label())) },
                    )
                }
            }
        }
    }
}
