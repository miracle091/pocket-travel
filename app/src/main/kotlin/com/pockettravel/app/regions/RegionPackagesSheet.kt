package com.pockettravel.app.regions

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.app.R
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.data.RegionZone
import com.pockettravel.core.data.RoutingVariantChoice
import com.pockettravel.core.data.routingIsStale
import com.pockettravel.core.sync.TransitDefaultReason
import com.pockettravel.core.sync.TransitMissing
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.LARGE_DOWNLOAD_WARNING_BYTES
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.isOnCellularNetwork
import com.pockettravel.core.ui.rememberExpandedSheetState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import com.pockettravel.core.ui.R as UiR

// Dettaglio di una regione installata: mappa, rete stradale (routing) e punti di interesse, ciascuno
// con il proprio stato e scaricabile, aggiornabile o eliminabile senza toccare gli altri.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RegionPackagesSheet(
    item: RegionUiItem,
    isDownloading: Boolean,
    actions: RegionRowActions,
    onDismiss: () -> Unit,
) {
    var pendingDelete by rememberSaveable { mutableStateOf<PackageKind?>(null) }
    val download = rememberPackageDownload(item.regionId, item.packages) { kind -> actions.onDownloadPackage(item.regionId, kind) }
    var pickingZone by rememberSaveable { mutableStateOf(false) }
    var showNetworks by rememberSaveable { mutableStateOf(false) }
    var showDeleteAll by rememberSaveable { mutableStateOf(false) }

    // Sempre aperto per intero: a meta' altezza (tablet in orizzontale) "Elimina tutto" resterebbe sotto il bordo.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberExpandedSheetState()) {
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
                            onDownload = { download(pkg) },
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
                        // Sempre visibili, anche con le reti chiuse: chi cerca una rete mancante legge subito il perche'.
                        if (pkg.staleNetworks.isNotEmpty()) TransitStaleNote(pkg.staleNetworks)
                        if (pkg.missingNetworks.isNotEmpty()) TransitMissingNote(pkg.missingNetworks)
                        if (pkg.kind == PackageKind.ROUTING && item.routingCarAvailable) {
                            val choice by remember(item.regionId) { actions.observeRoutingChoice(item.regionId) }
                                .collectAsStateWithLifecycle(RoutingVariantChoice.ALL)
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
                        UnavailablePackageRow(kind, item.splitCountry)
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
    if (showDeleteAll) {
        ConfirmationDialog(
            title = stringResource(R.string.regions_delete_title, item.displayName),
            message = stringResource(R.string.regions_delete_message),
            onConfirm = { showDeleteAll = false; onDismiss(); actions.onDelete(item.regionId) },
            onDismiss = { showDeleteAll = false },
        )
    }
}

/**
 * Il tocco su "Scarica" di un pacchetto di [packages]: parte subito, o dopo la conferma se e' grande sulla rete mobile.
 * Mostra la conferma: foglio Contenuti e schermata Aggiornamenti.
 */
@Composable
internal fun rememberPackageDownload(regionId: String, packages: List<PackageUiState>, onDownload: (PackageKind) -> Unit): (PackageUiState) -> Unit {
    val context = LocalContext.current
    var pendingLargeDownload by rememberSaveable(regionId) { mutableStateOf<PackageKind?>(null) }
    pendingLargeDownload?.let { kind ->
        // Dopo un ripristino lo stato salvato puo' riferirsi a un pacchetto che non c'e' piu' (o non piu' da aggiornare): si chiude il dialogo.
        val pkg = packages.firstOrNull { it.kind == kind }
        if (pkg == null) {
            SideEffect { pendingLargeDownload = null }
        } else {
            LargePackageDownloadDialog(
                pkg = pkg,
                onConfirm = { pendingLargeDownload = null; onDownload(kind) },
                onDismiss = { pendingLargeDownload = null },
            )
        }
    }
    return { pkg ->
        if (pkg.warnsOnCellular() && isOnCellularNetwork(context)) pendingLargeDownload = pkg.kind else onDownload(pkg.kind)
    }
}

/** La conferma di un pacchetto grande sulla rete mobile: foglio Contenuti e schermata Aggiornamenti. */
@Composable
internal fun LargePackageDownloadDialog(pkg: PackageUiState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmationDialog(
        title = stringResource(R.string.regions_large_download_title),
        // Il peso della mappa si conosce solo estraendola: senza dimensione un messaggio apposta.
        message = if (pkg.kind == PackageKind.MAP) {
            stringResource(R.string.regions_large_download_map_message)
        } else {
            stringResource(
                R.string.regions_large_download_message,
                stringResource(pkg.kind.label()),
                Formatter.formatShortFileSize(LocalContext.current, pkg.downloadBytes),
            )
        },
        confirmLabel = stringResource(R.string.regions_large_download_confirm),
        destructive = false,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

// La mappa si estrae sul dispositivo e il suo peso (downloadBytes = 0) non si conosce prima: avvisa sempre, come
// l'elenco delle regioni con includesMap.
internal fun PackageUiState.warnsOnCellular(): Boolean = kind == PackageKind.MAP || downloadBytes > LARGE_DOWNLOAD_WARNING_BYTES

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
                pkg.dataDate?.let { DataDate(it) }
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

// Il giorno dei dati della rete stradale; oltre ROUTING_STALE_MONTHS mesi lo dice in rosso.
@Composable
private fun DataDate(date: LocalDate) {
    val day = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(LocalLocale.current.platformLocale))
    val old = routingIsStale(date, LocalDate.now())
    Text(
        stringResource(if (old) R.string.package_data_date_old else R.string.package_data_date, day),
        color = if (old) MaterialTheme.colorScheme.error else Color.Unspecified,
    )
}

// Apre e chiude le reti dei mezzi pubblici, come la riga di una nazione divisa in regioni nell'elenco delle nazioni.
@Composable
private fun TransitNetworksToggle(selected: Int, total: Int, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "networksChevron")
    val stateText = stringResource(if (expanded) R.string.continent_expanded else R.string.continent_collapsed)
    val actionLabel = stringResource(if (expanded) R.string.transit_networks_collapse else R.string.transit_networks_expand)
    ListItem(
        trailingContent = { Icon(AppIcons.ExpandMore, contentDescription = null, modifier = Modifier.rotate(rotation)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.xl)
            .clickable(onClickLabel = actionLabel, role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) { stateDescription = stateText },
        content = { Text(pluralStringResource(R.plurals.transit_networks_selected, total, selected, total)) },
    )
}

// Una rete dei mezzi pubblici con la sua dimensione; cambiarla rende il pacchetto da scaricare o aggiornare.
@Composable
private fun TransitNetworkRow(network: TransitNetworkUi, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val size = if (network.downloadBytes > 0) Formatter.formatShortFileSize(LocalContext.current, network.downloadBytes) else null
    ListItem(
        content = { Text(network.name) },
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

// Sotto la rete stradale: per quali modi. Auto = solo le strade per l'auto (circa il 60% del peso); bici e piedi e tutti i
// modi = la rete stradale completa (una rete solo per bici e piedi peserebbe quasi uguale). Cambiare variante con la rete
// stradale installata la riscarica.
@Composable
private fun RoutingChoiceRow(choice: RoutingVariantChoice, enabled: Boolean, onChange: (RoutingVariantChoice) -> Unit) {
    ListItem(
        content = { Text(stringResource(R.string.package_routing_for)) },
        supportingContent = {
            Column {
                Text(stringResource(choice.routingDetail()))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = Spacing.s)) {
                    RoutingVariantChoice.entries.forEachIndexed { index, candidate ->
                        SegmentedButton(
                            selected = candidate == choice,
                            onClick = { onChange(candidate) },
                            enabled = enabled,
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = RoutingVariantChoice.entries.size),
                        ) { Text(stringResource(candidate.routingLabel())) }
                    }
                }
            }
        },
        leadingContent = { Icon(AppIcons.Car, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

// Sotto la mappa leggera, solo nelle nazioni grandi: la zona scaricata di mappa, rete stradale e civici.
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

// Sotto i mezzi pubblici: le reti non ancora incluse, una riga per motivo. I nomi contengono virgole: separati da ";".
@Composable
private fun TransitMissingNote(missing: List<TransitMissing>) {
    missing.groupBy { it.reason }.forEach { (reason, networks) ->
        val names = networks.joinToString("; ") { it.name }
        val text = when (reason) {
            "orari" -> stringResource(R.string.transit_missing_timetables, names)
            "licenza" -> stringResource(R.string.transit_missing_license, names)
            else -> stringResource(R.string.transit_missing_other, names)
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.xl + Spacing.l, end = Spacing.l, bottom = Spacing.s),
        )
    }
}

// Sotto i mezzi pubblici: le reti scaricate i cui orari non sono piu' aggiornati.
@Composable
private fun TransitStaleNote(names: List<String>) {
    Text(
        stringResource(R.string.transit_stale, names.joinToString("; ")),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(start = Spacing.xl + Spacing.l, end = Spacing.l, bottom = Spacing.s),
    )
}

// Pacchetto che il catalogo non offre per la regione: nessuna azione, solo il motivo. "regione" solo per una parte di
// una nazione divisa, come in zoneLabel; altrimenti "nazione" (es. San Marino).
@Composable
private fun UnavailablePackageRow(kind: PackageKind, splitCountry: Boolean) {
    ListItem(
        supportingContent = {
            Text(stringResource(if (splitCountry) R.string.package_status_unavailable else R.string.package_status_unavailable_country))
        },
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

// La mappa non ha una dimensione nota prima del download (estratta sul dispositivo): niente "0 B".
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

/** Per quali modi e' la rete stradale di una regione: solo [RoutingVariantChoice.CAR] scarica la variante "solo auto". */
@StringRes
internal fun RoutingVariantChoice.routingLabel(): Int = when (this) {
    RoutingVariantChoice.CAR -> R.string.package_routing_car
    RoutingVariantChoice.BIKE_FOOT -> R.string.package_routing_bike_foot
    RoutingVariantChoice.ALL -> R.string.package_routing_all
}

@StringRes
internal fun RoutingVariantChoice.routingDetail(): Int = when (this) {
    RoutingVariantChoice.CAR -> R.string.package_routing_car_only_detail
    RoutingVariantChoice.BIKE_FOOT -> R.string.package_routing_bike_foot_detail
    RoutingVariantChoice.ALL -> R.string.package_routing_all_detail
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
