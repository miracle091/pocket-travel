package com.pockettravel.app.regions

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pockettravel.app.R
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.ConfirmationDialog
import com.pockettravel.core.ui.LARGE_DOWNLOAD_WARNING_BYTES
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.isOnCellularNetwork

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
    var showDeleteAll by rememberSaveable { mutableStateOf(false) }

    // Sempre aperto per intero: a meta' altezza (tablet in orizzontale) "Elimina tutto" restava sotto il bordo.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
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

    pendingDelete?.let { kind ->
        ConfirmationDialog(
            title = stringResource(kind.deleteTitle(), item.displayName),
            message = stringResource(R.string.package_delete_message),
            onConfirm = { pendingDelete = null; actions.onDeletePackage(item.regionId, kind) },
            onDismiss = { pendingDelete = null },
        )
    }
    pendingLargeDownload?.let { kind ->
        val size = Formatter.formatShortFileSize(context, item.packages.first { it.kind == kind }.downloadBytes)
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
        headlineContent = { Text(name) },
        supportingContent = { Text(supporting) },
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
    )
}

// Pacchetto che il manifest non offre per la regione: nessuna azione, solo il motivo.
@Composable
private fun UnavailablePackageRow(kind: PackageKind) {
    ListItem(
        headlineContent = { Text(stringResource(kind.label())) },
        supportingContent = { Text(stringResource(R.string.package_status_unavailable)) },
        leadingContent = { Icon(kind.icon(), contentDescription = null) },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = MaterialTheme.colorScheme.onSurfaceVariant,
            leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
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
    PackageKind.ADDRESSES -> R.string.package_addresses
}

@StringRes
internal fun PackageKind.deleteTitle(): Int = when (this) {
    PackageKind.MAP -> R.string.package_delete_title_map
    PackageKind.ROUTING -> R.string.package_delete_title_routing
    PackageKind.POI -> R.string.package_delete_title_poi
    PackageKind.ADDRESSES -> R.string.package_delete_title_addresses
}

@Composable
internal fun PackageKind.icon(): ImageVector = when (this) {
    PackageKind.MAP -> AppIcons.Map
    PackageKind.ROUTING -> AppIcons.Route
    PackageKind.POI -> AppIcons.Place
    PackageKind.ADDRESSES -> AppIcons.HomePin
}
