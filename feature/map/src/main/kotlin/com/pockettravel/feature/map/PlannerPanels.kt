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
import androidx.compose.material3.TextButton
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
// Un modo dell'anteprima: scelto con icona e nome, gli altri solo con l'icona (il nome per TalkBack).
@Composable
private fun ModeChip(icon: Int, name: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
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

// pannello agganciato al bordo in alto, come le indicazioni di Google Maps, che lascia libera la mappa.
@Composable
internal fun RoutePanel(
    from: NavigationPlace?,
    to: NavigationPlace,
    onEditFrom: () -> Unit,
    onEditTo: () -> Unit,
    onSwap: () -> Unit,
    waypoints: List<NavigationPlace>,
    onAddWaypoint: () -> Unit,
    onRemoveWaypoint: (Int) -> Unit,
    onMoveWaypoint: (index: Int, earlier: Boolean) -> Unit,
    routeProfile: RouteProfile,
    onRouteProfileChange: (RouteProfile) -> Unit,
    avoid: Set<RouteAvoid> = emptySet(),
    onAvoidChange: (RouteAvoid, Boolean) -> Unit = { _, _ -> },
    // Modo «Mezzi» (itinerari con i mezzi pubblici) scelto; [onTransit] null nasconde il chip.
    transit: Boolean = false,
    onTransit: (() -> Unit)? = null,
    // La stella della destinazione: [favorite] se e' tra i preferiti.
    favorite: Boolean = false,
    onSaveFavorite: (name: String) -> Unit = {},
    onRemoveFavorite: () -> Unit = {},
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
                    waypoints.forEachIndexed { index, waypoint ->
                        WaypointField(index, waypoint, last = index == waypoints.lastIndex, onRemoveWaypoint, onMoveWaypoint)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlaceField(
                            icon = AppIcons.Place,
                            iconTint = MaterialTheme.colorScheme.error,
                            label = stringResource(R.string.planner_to),
                            text = to.name,
                            onClick = onEditTo,
                            modifier = Modifier.weight(1f),
                        )
                        FavoriteToggle(to, favorite, onSaveFavorite, onRemoveFavorite)
                    }
                }
                // Con la propria posizione come partenza non si scambia: l'arrivo resterebbe senza destinazione.
                IconButton(onClick = onSwap, enabled = from != null) {
                    Icon(ImageVector.vectorResource(UiR.drawable.ms_swap_vert), contentDescription = stringResource(R.string.planner_swap))
                }
            }
            if (waypoints.size < MAX_WAYPOINTS) {
                TextButton(onClick = onAddWaypoint) {
                    Icon(ImageVector.vectorResource(UiR.drawable.ms_add), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(Spacing.s))
                    Text(stringResource(R.string.planner_add_waypoint))
                }
            }
            // Solo il modo scelto ha anche il nome: i chip con l'icona stanno in una riga stretta.
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.padding(top = Spacing.xs)) {
                RouteProfile.entries.forEach { mode ->
                    val (icon, text) = when (mode) {
                        RouteProfile.WALK -> UiR.drawable.ms_directions_walk to R.string.usage_mode_walk
                        RouteProfile.BIKE -> UiR.drawable.ms_directions_bike to R.string.usage_mode_bike
                        RouteProfile.CAR -> UiR.drawable.ms_directions_car to R.string.usage_mode_car
                    }
                    ModeChip(icon, stringResource(text), selected = mode == routeProfile && !transit) { onRouteProfileChange(mode) }
                }
                onTransit?.let { ModeChip(UiR.drawable.ms_directions_bus, stringResource(R.string.usage_mode_transit), selected = transit, onClick = it) }
                // Le opzioni («Evita», scale) valgono per i percorsi di BRouter, non per i mezzi pubblici.
                if (!transit) RouteOptionsChip(routeProfile, avoid, onAvoidChange)
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
// Una tappa: il nome, le frecce per spostarla (solo con piu' tappe) e «togli». Si cambia togliendola e aggiungendone un'altra.
@Composable
private fun WaypointField(
    index: Int,
    waypoint: NavigationPlace,
    last: Boolean,
    onRemove: (Int) -> Unit,
    onMove: (index: Int, earlier: Boolean) -> Unit,
) {
    val description = stringResource(R.string.planner_place_field, stringResource(R.string.planner_waypoint_number, index + 1), waypoint.name)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.weight(1f).heightIn(min = 44.dp).clearAndSetSemantics { contentDescription = description },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Spacing.m)) {
                Icon(ImageVector.vectorResource(UiR.drawable.ms_location_on), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(Spacing.m))
                Text(waypoint.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (index > 0 || !last) {
            IconButton(onClick = { onMove(index, true) }, enabled = index > 0) {
                Icon(ImageVector.vectorResource(UiR.drawable.ms_arrow_upward), contentDescription = stringResource(R.string.planner_waypoint_earlier, waypoint.name))
            }
            IconButton(onClick = { onMove(index, false) }, enabled = !last) {
                Icon(ImageVector.vectorResource(UiR.drawable.ms_arrow_downward), contentDescription = stringResource(R.string.planner_waypoint_later, waypoint.name))
            }
        }
        IconButton(onClick = { onRemove(index) }) {
            Icon(ImageVector.vectorResource(UiR.drawable.ms_close), contentDescription = stringResource(R.string.planner_remove_waypoint, waypoint.name))
        }
    }
}

@Composable
private fun PlaceField(icon: ImageVector, iconTint: Color, label: String, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.planner_place_field, label, text)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { contentDescription = description },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Spacing.m)) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(Spacing.m))
            Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
