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
        listOf(PoiCategory.ATTRAZIONI, PoiCategory.SVAGO, PoiCategory.PARCO_GIOCHI, PoiCategory.TAVOLI_PICNIC),
    ),
    SERVICES(
        R.string.map_legend_group_services,
        listOf(
            PoiCategory.NEGOZI, PoiCategory.DISTRIBUTORI, PoiCategory.BANCA, PoiCategory.BANCOMAT, PoiCategory.UFFICIO_POSTALE,
            PoiCategory.CASSETTA_POSTALE, PoiCategory.INFORMAZIONI, PoiCategory.BAGNI_PUBBLICI, PoiCategory.ACQUA_POTABILE,
            PoiCategory.AMBASCIATA_CONSOLATO,
        ),
    ),
    TRANSPORT(
        R.string.map_legend_group_transport,
        listOf(
            PoiCategory.TRENO, PoiCategory.METRO, PoiCategory.AUTOBUS, PoiCategory.TAXI, PoiCategory.TRAGHETTO,
            PoiCategory.AEROPORTO, PoiCategory.NOLEGGIO, PoiCategory.CARBURANTE, PoiCategory.RICARICA, PoiCategory.PARCHEGGIO,
            PoiCategory.PARCHEGGIO_PRIVATO,
        ),
    ),
    HEALTH(
        R.string.map_legend_group_health,
        listOf(PoiCategory.FARMACIA, PoiCategory.OSPEDALE, PoiCategory.POLIZIA, PoiCategory.VIGILI_DEL_FUOCO, PoiCategory.VETERINARIO),
    ),
    OTHER(R.string.map_legend_group_other, listOf(PoiCategory.ALTRO)),
}

// Tutte le categorie presenti nella regione, a gruppi, ciascuna con il suo interruttore: le stesse
// scelte dei chip sopra la mappa, salvate per tutte le regioni (MapFilterPreferences).
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
                Text(
                    text = stringResource(group.label),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.l, bottom = Spacing.xs)
                        .semantics { heading() },
                )
                categories.forEach { category ->
                    val visible = category !in hiddenCategories
                    ListItem(
                        headlineContent = { Text(stringResource(category.label())) },
                        leadingContent = { PoiBadge(category, size = 32) },
                        trailingContent = { Switch(checked = visible, onCheckedChange = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .toggleable(value = visible, role = Role.Switch) {
                                onHiddenCategoriesChange(if (visible) hiddenCategories + category else hiddenCategories - category)
                            }
                            .padding(horizontal = Spacing.s),
                    )
                }
            }
        }
    }
}
