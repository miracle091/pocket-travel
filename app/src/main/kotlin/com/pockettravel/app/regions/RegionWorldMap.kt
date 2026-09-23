package com.pockettravel.app.regions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.pockettravel.app.R
import com.pockettravel.core.ui.Spacing
import com.pockettravel.feature.map.CountryStatus
import com.pockettravel.feature.map.WorldMap
import java.util.Locale

/**
 * Mappa del mondo con i paesi del catalogo: tocco su un paese con una sola regione -> quella
 * regione (anteprima se non scaricata, come nell'elenco); con piu' regioni (es. Stati Uniti) ->
 * scelta in un bottom sheet; paese non ancora nel catalogo -> un bottom sheet che lo dice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RegionWorldMap(
    items: List<RegionUiItem>,
    rowActions: RegionRowActions,
    onRegionClick: (RegionUiItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val regionsByCountry = remember(items) { items.filter { it.countryCode != null }.groupBy { it.countryCode!! } }
    val countryStatus = remember(regionsByCountry) {
        regionsByCountry.mapValues { (_, regions) ->
            if (regions.any { it.status != RegionStatus.NOT_INSTALLED }) CountryStatus.DOWNLOADED else CountryStatus.AVAILABLE
        }
    }
    var selectedIso by rememberSaveable { mutableStateOf<String?>(null) }

    WorldMap(
        countryStatus = countryStatus,
        onCountryClick = { iso ->
            val regions = regionsByCountry[iso].orEmpty()
            if (regions.size == 1) onRegionClick(regions.single()) else selectedIso = iso
        },
        modifier = modifier,
    )

    selectedIso?.let { iso ->
        val regions = regionsByCountry[iso].orEmpty()
        val countryName = Locale("", iso.uppercase()).getDisplayCountry(Locale.ITALIAN)
        ModalBottomSheet(onDismissRequest = { selectedIso = null }) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.xxl)) {
                Text(
                    text = countryName,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s).semantics { heading() },
                )
                if (regions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.world_map_not_available),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.xl),
                    )
                } else {
                    regions.sortedBy { it.displayName }.forEach { region ->
                        RegionRow(item = region, actions = rowActions, onClick = { selectedIso = null; onRegionClick(region) })
                    }
                }
            }
        }
    }
}
