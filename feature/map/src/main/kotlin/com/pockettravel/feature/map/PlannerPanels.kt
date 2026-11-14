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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
    avoid: Set<RouteAvoid> = emptySet(),
    onAvoidChange: (RouteAvoid, Boolean) -> Unit = { _, _ -> },
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
                RouteOptionsChip(routeProfile, avoid, onAvoidChange)
            }
        }
    }
}

// "Opzioni": cosa evitare con il modo scelto, solo per questo percorso. Accanto al nome lungo del modo scelto sta solo
// l'icona; con opzioni attive il chip e' selezionato e le conta. TalkBack legge il nome e quante sono.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteOptionsChip(routeProfile: RouteProfile, avoid: Set<RouteAvoid>, onAvoidChange: (RouteAvoid, Boolean) -> Unit) {
    val options = RouteAvoid.forMode(routeProfile)
    if (options.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    val active = options.count { it in avoid }
    val description = if (active > 0) stringResource(R.string.planner_route_options_count, active) else stringResource(R.string.planner_route_options)
    val icon = ImageVector.vectorResource(UiR.drawable.ms_filter_list)
    FilterChip(
        selected = active > 0,
        onClick = { open = true },
        label = {
            if (active > 0) Text("$active", modifier = Modifier.clearAndSetSemantics {}) else Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        },
        leadingIcon = if (active > 0) {
            { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
        modifier = Modifier.semantics { contentDescription = description },
    )
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Column(modifier = Modifier.padding(start = Spacing.m, end = Spacing.m, bottom = Spacing.l)) {
                Text(stringResource(R.string.planner_route_options), style = MaterialTheme.typography.titleLarge)
                options.forEach { option -> RouteAvoidSwitch(option, option in avoid) { onAvoidChange(option, it) } }
                Text(
                    stringResource(R.string.planner_route_options_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.s),
                )
            }
        }
    }
}

/** Una riga con interruttore per un'opzione "Evita": nell'anteprima del percorso e nelle Impostazioni. */
@Composable
fun RouteAvoidSwitch(option: RouteAvoid, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        Text(stringResource(option.label()), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Le opzioni "Evita" di auto e bici, a gruppi per modo: il foglio di Impostazioni > Opzioni di percorso. */
@Composable
fun RouteAvoidOptions(avoid: Set<RouteAvoid>, onAvoidChange: (Set<RouteAvoid>) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        listOf(RouteProfile.CAR to R.string.route_avoid_car, RouteProfile.BIKE to R.string.route_avoid_bike).forEach { (mode, title) ->
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = Spacing.m))
            RouteAvoid.forMode(mode).forEach { option ->
                RouteAvoidSwitch(option, option in avoid) { on -> onAvoidChange(if (on) avoid + option else avoid - option) }
            }
        }
    }
}

/** Le opzioni attive in una riga, per il sottotitolo nelle Impostazioni. */
@Composable
fun routeAvoidSummary(avoid: Set<RouteAvoid>): String =
    if (avoid.isEmpty()) stringResource(R.string.route_avoid_none) else RouteAvoid.entries.filter { it in avoid }.map { stringResource(it.label()) }.joinToString(", ")

private fun RouteAvoid.label(): Int = when (this) {
    RouteAvoid.TOLLS -> R.string.route_avoid_tolls
    RouteAvoid.MOTORWAYS -> R.string.route_avoid_motorways
    RouteAvoid.UNPAVED -> R.string.route_avoid_unpaved
    RouteAvoid.BUSY_ROADS -> R.string.route_avoid_busy_roads
    RouteAvoid.FERRIES -> R.string.route_avoid_ferries
}

// Riga bassa come un campo di testo, senza etichetta visibile (la dice l'icona): TalkBack la legge.
@Composable
private fun PlaceField(icon: ImageVector, iconTint: Color, label: String, text: String, onClick: () -> Unit) {
    val description = stringResource(R.string.planner_place_field, label, text)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { contentDescription = description },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Spacing.m)) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(Spacing.m))
            Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
