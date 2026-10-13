package com.pockettravel.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.R as UiR

// "Dove vuoi andare?": la barra a pillola in cima alla mappa, come la ricerca di Google Maps.
@Composable
internal fun SearchPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Spacing.l)) {
            Icon(AppIcons.Search, contentDescription = null)
            Spacer(modifier = Modifier.width(Spacing.m))
            Text(stringResource(R.string.planner_where_to), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// Partenza e arrivo su due righe basse, il pulsante per scambiarli e i modi in chip piccole: un
// pannello agganciato al bordo in alto, come le indicazioni di Google Maps, che lascia libera la mappa.
@Composable
internal fun RoutePanel(
    from: NavigationPlace?,
    to: NavigationPlace,
    onEditFrom: () -> Unit,
    onEditTo: () -> Unit,
    onSwap: () -> Unit,
    routeProfile: RouteProfile,
    onRouteProfileChange: (RouteProfile) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = Spacing.m, top = Spacing.s, bottom = Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.weight(1f)) {
                    PlaceField(
                        icon = ImageVector.vectorResource(UiR.drawable.ms_trip_origin),
                        iconTint = MaterialTheme.colorScheme.primary,
                        label = stringResource(R.string.planner_from),
                        text = from?.name ?: stringResource(R.string.planner_my_position),
                        onClick = onEditFrom,
                    )
                    PlaceField(
                        icon = AppIcons.Place,
                        iconTint = MaterialTheme.colorScheme.error,
                        label = stringResource(R.string.planner_to),
                        text = to.name,
                        onClick = onEditTo,
                    )
                }
                // Con la propria posizione come partenza non si scambia: l'arrivo resterebbe senza destinazione.
                IconButton(onClick = onSwap, enabled = from != null) {
                    Icon(ImageVector.vectorResource(UiR.drawable.ms_swap_vert), contentDescription = stringResource(R.string.planner_swap))
                }
            }
            // Solo il modo scelto ha anche il nome: tre chip con l'icona stanno in una riga stretta.
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.padding(top = Spacing.xs)) {
                RouteProfile.entries.forEach { mode ->
                    val (icon, text) = when (mode) {
                        RouteProfile.WALK -> UiR.drawable.ms_directions_walk to R.string.usage_mode_walk
                        RouteProfile.BIKE -> UiR.drawable.ms_directions_bike to R.string.usage_mode_bike
                        RouteProfile.CAR -> UiR.drawable.ms_directions_car to R.string.usage_mode_car
                    }
                    val selected = mode == routeProfile
                    val name = stringResource(text)
                    FilterChip(
                        selected = selected,
                        onClick = { onRouteProfileChange(mode) },
                        label = {
                            if (selected) {
                                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            } else {
                                Icon(ImageVector.vectorResource(icon), contentDescription = name, modifier = Modifier.size(18.dp))
                            }
                        },
                        leadingIcon = if (selected) {
                            { Icon(ImageVector.vectorResource(icon), contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

// Riga bassa come un campo di testo, senza etichetta visibile (la dice l'icona): TalkBack la legge.
@Composable
private fun PlaceField(icon: ImageVector, iconTint: Color, label: String, text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { contentDescription = "$label: $text" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Spacing.m)) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(Spacing.m))
            Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
