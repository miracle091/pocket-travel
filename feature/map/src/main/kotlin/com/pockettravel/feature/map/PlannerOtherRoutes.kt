package com.pockettravel.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.R as UiR

/**
 * «Altri percorsi» sotto tempo e distanza: il pulsante che li calcola, poi un chip con tempo e distanza per ogni
 * percorso trovato (il principale per primo); toccando un chip si sceglie quel percorso.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OtherRoutesRow(preview: PlannerPreview.Ready, onFind: () -> Unit, onSelect: (Route) -> Unit) {
    when (val others = preview.others) {
        OtherRoutes.NotAsked ->
            // Senza il margine sinistro del TextButton l'icona si allinea al tempo e alla distanza sopra, come «Imposta un orario di arrivo».
            TextButton(onClick = onFind, contentPadding = PaddingValues(end = Spacing.l, top = Spacing.s, bottom = Spacing.s)) {
                Icon(ImageVector.vectorResource(UiR.drawable.ms_alt_route), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(Spacing.s))
                Text(stringResource(R.string.planner_other_routes))
            }
        OtherRoutes.Calculating -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Spacing.m)) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(Spacing.s))
            Text(stringResource(R.string.planner_other_routes_searching), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        is OtherRoutes.Found -> if (others.routes.size < 2) {
            Text(
                stringResource(R.string.planner_no_other_routes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.m).semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                others.routes.forEach { route ->
                    FilterChip(
                        selected = route == preview.route,
                        onClick = { onSelect(route) },
                        label = { Text(stringResource(R.string.planner_route_choice, durationText(route.durationSeconds), distanceText(route.distanceMeters))) },
                    )
                }
            }
        }
    }
}
