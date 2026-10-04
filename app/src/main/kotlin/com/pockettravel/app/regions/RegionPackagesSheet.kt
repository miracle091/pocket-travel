package com.pockettravel.app.regions

import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.animateFloatAsState
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.app.R
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionZone
import com.pockettravel.core.sync.TransitDefaultReason
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.LARGE_DOWNLOAD_WARNING_BYTES
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.isOnCellularNetwork
import com.pockettravel.core.ui.R as UiR

// Dettaglio di una regione installata: mappa, percorsi (routing) e punti di interesse, ciascuno
// con il proprio stato e scaricabile, aggiornabile o eliminabile senza toccare gli altri.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RegionPackagesSheet(
    item: RegionUiItem,
    isDownloading: Boolean,
    actions: RegionRowActions,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var pendingDelete by rememberSaveable { mutableStateOf<PackageKind?>(null) }
    var pendingLargeDownload by rememberSaveable { mutableStateOf<PackageKind?>(null) }
    var pickingZone by rememberSaveable { mutableStateOf(false) }
    var showNetworks by rememberSaveable { mutableStateOf(false) }
    var showDeleteAll by rememberSaveable { mutableStateOf(false) }

    // Sempre aperto per intero: a meta' altezza (tablet in orizzontale) "Elimina tutto" resterebbe sotto il bordo.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Spacing.l),
        ) {
            Text(
                text = stringResource(R.string.regions_packages, item.displayName),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = Spacing.l, bottom = Spacing.m).semantics { heading() },
            )
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    item.packages.forEachIndexed { index, pkg ->
                        PackageRow(
                            pkg = pkg,
                            enabled = !isDownloading,
                            onDownload = {
                                if (pkg.downloadBytes > LARGE_DOWNLOAD_WARNING_BYTES && isOnCellularNetwork(context)) {
                                    pendingLargeDownload = pkg.kind
                                } else {
                                    actions.onDownloadPackage(item.regionId, pkg.kind)
                                }
                            },
                            onDelete = { pendingDelete = pkg.kind },
                        )
                        // Piu' reti: a scomparsa sotto "N di M reti scelte", chiuse di default. Una sola: la sua casella.
                        if (pkg.networks.size > 1) {
                            TransitNetworksToggle(
                                selected = pkg.networks.count { it.selected },
                                total = pkg.networks.size,
                                expanded = showNetworks,
                                onToggle = { showNetworks = !showNetworks },
                            )
                        }
                        if (pkg.networks.size == 1 || showNetworks) {
                            // Una casella per rete: l'ultima scelta non si puo' togliere (niente pacchetto vuoto).
                            pkg.networks.forEach { network ->
                                TransitNetworkRow(
                                    network = network,
                                    enabled = !isDownloading && !(network.selected && pkg.networks.count { it.selected } == 1),
                                    onChange = { included -> actions.onTransitNetworkChange(item.regionId, network.id, included) },
                                )
                            }
                            pkg.transitDefaultReason?.let { TransitNetworksNote(it) }
                        }
                        if (pkg.kind == PackageKind.ROUTING && item.routingCarAvailable) {
                            val choice by remember(item.regionId) { actions.observeRoutingChoice(item.regionId) }
                                .collectAsStateWithLifecycle(RoutingChoice.ALL)
                            RoutingChoiceRow(choice = choice, enabled = !isDownloading, onChange = { actions.onRoutingChoiceChange(item.regionId, it) })
                        }
                        if (pkg.kind == PackageKind.MAP) {
                            val light by remember(item.regionId) { actions.observeMapLight(item.regionId) }.collectAsStateWithLifecycle(false)
                            MapLightRow(light = light, enabled = !isDownloading, onChange = { actions.onMapLightChange(item.regionId, it) })
                            if (item.bbox != null && (item.bbox.isLarge() || item.zone != null)) {
                                ZoneRow(zone = item.zone, splitCountry = item.splitCountry, enabled = !isDownloading, onClick = { pickingZone = true })
                            }
                        }
                        if (index < item.packages.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    }
                    item.unavailableKinds.forEach { kind ->
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                        UnavailablePackageRow(kind)
                    }
                }
            }
            TextButton(
                onClick = { showDeleteAll = true },
                enabled = !isDownloading,
                modifier = Modifier.padding(vertical = Spacing.s),
            ) {
                Text(stringResource(R.string.regions_delete_all), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (pickingZone && item.bbox != null) {
        ZoneSelection(item = item, download = false, actions = actions, onDone = { pickingZone = false })
    }

    pendingDelete?.let { kind ->
        ConfirmationDialog(
            title = stringResource(kind.deleteTitle(), item.displayName),
            message = stringResource(R.string.package_delete_message),
            onConfirm = { pendingDelete = null; actions.onDeletePackage(item.regionId, kind) },
            onDismiss = { pendingDelete = null },
        )
    }
    pendingLargeDownload?.let { kind ->
        // Dopo un ripristino lo stato salvato puo' riferirsi a un pacchetto che non c'e' piu': si chiude il dialogo.
        val pkg = item.packages.firstOrNull { it.kind == kind }
        if (pkg == null) {
            SideEffect { pendingLargeDownload = null }
            return@let
        }
        val size = Formatter.formatShortFileSize(context, pkg.downloadBytes)
        ConfirmationDialog(
            title = stringResource(R.string.regions_large_download_title),
            message = stringResource(R.string.regions_large_download_message, stringResource(kind.label()), size),
            confirmLabel = stringResource(R.string.regions_large_download_confirm),
            destructive = false,
            onConfirm = { pendingLargeDownload = null; actions.onDownloadPackage(item.regionId, kind) },
            onDismiss = { pendingLargeDownload = null },
        )
    }
    if (showDeleteAll) {
        ConfirmationDialog(
            title = stringResource(R.string.regions_delete_title, item.displayName),
            message = stringResource(R.string.regions_delete_message),
            onConfirm = { showDeleteAll = false; onDismiss(); actions.onDelete(item.regionId) },
            onDismiss = { showDeleteAll = false },
        )
    }
}

@Composable
private fun PackageRow(pkg: PackageUiState, enabled: Boolean, onDownload: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val name = stringResource(pkg.kind.label())
    val supporting = when (pkg.status) {
        RegionStatus.INSTALLED -> pkg.installedBytes
            ?.let { stringResource(R.string.package_status_installed, Formatter.formatShortFileSize(context, it)) }
            ?: stringResource(R.string.package_status_installed_no_size)
        RegionStatus.UPDATE_AVAILABLE -> sizedStatus(R.string.package_status_update, R.string.package_status_update_no_size, pkg.downloadBytes)
        RegionStatus.NOT_INSTALLED -> sizedStatus(R.string.package_status_not_installed, R.string.package_status_not_installed_no_size, pkg.downloadBytes)
    }
    ListItem(
        supportingContent = {
            Column {
                // Le reti dei mezzi pubblici incluse nel pacchetto.
                pkg.detail?.let { Text(it) }
                Text(supporting)
            }
        },
        leadingContent = { Icon(pkg.kind.icon(), contentDescription = null) },
        trailingContent = {
            when (pkg.status) {
                RegionStatus.NOT_INSTALLED -> IconButton(onClick = onDownload, enabled = enabled) {
                    Icon(AppIcons.Download, contentDescription = stringResource(R.string.package_download, name))
                }
                RegionStatus.UPDATE_AVAILABLE -> TextButton(onClick = onDownload, enabled = enabled) {
                    Text(stringResource(R.string.regions_update))
                }
                RegionStatus.INSTALLED -> IconButton(onClick = onDelete, enabled = enabled) {
                    Icon(AppIcons.Delete, contentDescription = stringResource(R.string.package_delete, name))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
        content = { Text(name) },
    )
}

// Apre e chiude le reti dei mezzi pubblici, come la riga di un paese diviso in regioni nell'elenco delle nazioni.
@Composable
private fun TransitNetworksToggle(selected: Int, total: Int, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "networksChevron")
    val stateText = stringResource(if (expanded) R.string.continent_expanded else R.string.continent_collapsed)
    val actionLabel = stringResource(if (expanded) R.string.continent_collapse else R.string.continent_expand)
    ListItem(
        trailingContent = { Icon(AppIcons.ExpandMore, contentDescription = null, modifier = Modifier.rotate(rotation)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.xl)
            .clickable(onClickLabel = actionLabel, role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) { stateDescription = stateText },
        content = { Text(stringResource(R.string.transit_networks_selected, selected, total)) },
    )
}

// Una rete dei mezzi pubblici con la sua dimensione; cambiarla rende il pacchetto da scaricare o aggiornare.
@Composable
private fun TransitNetworkRow(network: TransitNetworkUi, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val size = if (network.downloadBytes > 0) Formatter.formatShortFileSize(LocalContext.current, network.downloadBytes) else null
    ListItem(
        headlineContent = { Text(network.name) },
        supportingContent = size?.let { { Text(it) } },
        leadingContent = { Checkbox(checked = network.selected, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.xl)
            .toggleable(value = network.selected, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
    )
}

// Sotto la mappa: leggera (senza la z14) o dettagliata. Cambiarla con la mappa installata la riestrae subito.
@Composable
private fun MapLightRow(light: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    // Stessa variante di ListItem di PackageRow (content al posto di headlineContent), con la stessa spaziatura:
    // icona e testo allineati alla riga della mappa sopra.
    ListItem(
        content = { Text(stringResource(R.string.package_map_light)) },
        supportingContent = { Text(stringResource(R.string.package_map_light_detail)) },
        leadingContent = { Icon(AppIcons.MapLight, contentDescription = null) },
        // Un poco rientrato, verso le icone delle altre righe (al centro di un IconButton da 48 dp).
        trailingContent = { Switch(checked = light, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(end = Spacing.xs)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = light, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}

// Sotto la rete stradale: per quali mezzi. Auto = solo le strade per l'auto (circa il 60% del peso); bici e piedi e tutti i
// mezzi = il pacchetto completo (uno solo per bici e piedi peserebbe quasi uguale). Cambiare pacchetto con i percorsi
// installati li riscarica.
@Composable
private fun RoutingChoiceRow(choice: RoutingChoice, enabled: Boolean, onChange: (RoutingChoice) -> Unit) {
    ListItem(
        content = { Text(stringResource(R.string.package_routing_for)) },
        supportingContent = {
            Column {
                Text(stringResource(choice.detail))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = Spacing.s)) {
                    RoutingChoice.entries.forEachIndexed { index, candidate ->
                        SegmentedButton(
                            selected = candidate == choice,
                            onClick = { onChange(candidate) },
                            enabled = enabled,
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = RoutingChoice.entries.size),
                        ) { Text(stringResource(candidate.label)) }
                    }
                }
            }
        },
        leadingContent = { Icon(AppIcons.Car, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Per quali mezzi sono i percorsi di una regione: solo [CAR] scarica la variante "solo auto". */
enum class RoutingChoice(@StringRes val label: Int, @StringRes val detail: Int) {
    CAR(R.string.package_routing_car, R.string.package_routing_car_only_detail),
    BIKE_FOOT(R.string.package_routing_bike_foot, R.string.package_routing_bike_foot_detail),
    ALL(R.string.package_routing_all, R.string.package_routing_all_detail),
}

// Sotto la mappa leggera, solo nei paesi grandi: la zona scaricata di mappa, percorsi e civici.
@Composable
private fun ZoneRow(zone: RegionZone?, splitCountry: Boolean, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        content = { Text(stringResource(R.string.zone_row)) },
        supportingContent = { Text(zoneLabel(zone, splitCountry)) },
        leadingContent = { Icon(AppIcons.Zone, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
    )
}

// Sotto le reti, con la scelta di default: perche' sono spuntate quelle.
@Composable
private fun TransitNetworksNote(reason: TransitDefaultReason) {
    val text = when (reason) {
        TransitDefaultReason.NEAR -> stringResource(R.string.transit_networks_near)
        TransitDefaultReason.NEAREST -> stringResource(R.string.transit_networks_nearest)
        TransitDefaultReason.ALL -> stringResource(R.string.transit_networks_all)
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.xl + Spacing.l, end = Spacing.l, bottom = Spacing.s),
    )
}

// Pacchetto che il manifest non offre per la regione: nessuna azione, solo il motivo.
@Composable
private fun UnavailablePackageRow(kind: PackageKind) {
    ListItem(
        supportingContent = { Text(stringResource(R.string.package_status_unavailable)) },
        leadingContent = { Icon(kind.icon(), contentDescription = null) },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = MaterialTheme.colorScheme.onSurfaceVariant,
            leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
        content = { Text(stringResource(kind.label())) },
    )
}

// La mappa non ha una dimensione nota prima del download (estratta sul device): niente "0 B".
@Composable
private fun sizedStatus(@StringRes withSize: Int, @StringRes withoutSize: Int, bytes: Long): String =
    if (bytes > 0) stringResource(withSize, Formatter.formatShortFileSize(LocalContext.current, bytes)) else stringResource(withoutSize)

@StringRes
internal fun PackageKind.label(): Int = when (this) {
    PackageKind.MAP -> R.string.package_map
    PackageKind.ROUTING -> R.string.package_routing
    PackageKind.POI -> R.string.package_poi
    PackageKind.POI_EXTRA -> R.string.package_poi_extra
    PackageKind.ADDRESSES -> R.string.package_addresses
    PackageKind.CITIES -> R.string.package_cities
    PackageKind.TRANSIT -> R.string.package_transit
}

@StringRes
internal fun PackageKind.deleteTitle(): Int = when (this) {
    PackageKind.MAP -> R.string.package_delete_title_map
    PackageKind.ROUTING -> R.string.package_delete_title_routing
    PackageKind.POI -> R.string.package_delete_title_poi
    PackageKind.POI_EXTRA -> R.string.package_delete_title_poi_extra
    PackageKind.ADDRESSES -> R.string.package_delete_title_addresses
    PackageKind.CITIES -> R.string.package_delete_title_cities
    PackageKind.TRANSIT -> R.string.package_delete_title_transit
}

@Composable
internal fun PackageKind.icon(): ImageVector = when (this) {
    PackageKind.MAP -> AppIcons.Map
    PackageKind.ROUTING -> AppIcons.Route
    PackageKind.POI -> AppIcons.Place
    PackageKind.POI_EXTRA -> AppIcons.AddLocation
    PackageKind.ADDRESSES -> AppIcons.HomePin
    PackageKind.CITIES -> AppIcons.Cities
    PackageKind.TRANSIT -> ImageVector.vectorResource(UiR.drawable.ms_directions_bus)
}
