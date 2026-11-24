package com.pockettravel.app.updates

import android.text.format.Formatter
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pockettravel.app.R
import com.pockettravel.app.regions.PackageUiState
import com.pockettravel.app.regions.RegionRowActions
import com.pockettravel.app.regions.RegionStatus
import com.pockettravel.app.regions.RegionUiItem
import com.pockettravel.app.regions.label
import com.pockettravel.app.regions.rememberPackageDownload
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.ui.Spacing

/**
 * Sotto una nazione da aggiornare, cosa cambia: un elenco puntato dei pacchetti con il loro peso. Con piu' di un pacchetto
 * ognuno ha il suo "Aggiorna", oltre a quello della nazione; sulla rete mobile un pacchetto grande chiede conferma, come nel
 * foglio Contenuti.
 */
@Composable
internal fun UpdatePackageList(item: RegionUiItem, actions: RegionRowActions) {
    val packages = item.packages.filter { it.status == RegionStatus.UPDATE_AVAILABLE }
    val download = rememberPackageDownload(item.regionId, packages) { kind -> actions.onDownloadPackage(item.regionId, kind) }
    packages.forEach { pkg ->
        UpdatePackageRow(pkg, item.displayName, showButton = packages.size > 1, enabled = !item.fromSavedCopy) { download(pkg) }
    }
    if (packages.isNotEmpty()) Spacer(Modifier.height(Spacing.s))
}

@Composable
private fun UpdatePackageRow(pkg: PackageUiState, regionName: String, showButton: Boolean, enabled: Boolean, onUpdate: () -> Unit) {
    val name = stringResource(pkg.kind.label())
    val updateDescription = stringResource(R.string.updates_package_update, name, regionName)
    // La mappa ha peso 0, si conosce solo estraendola: circa quanto quella installata, se il suo peso è noto.
    val context = LocalContext.current
    val mapEstimate = pkg.installedBytes?.takeIf { pkg.kind == PackageKind.MAP && it > 0 }
    val text = when {
        pkg.downloadBytes > 0 -> stringResource(R.string.updates_package_line, name, Formatter.formatShortFileSize(context, pkg.downloadBytes))
        mapEstimate != null -> stringResource(
            R.string.updates_package_line,
            name,
            stringResource(R.string.updates_size_about, Formatter.formatShortFileSize(context, mapEstimate)),
        )
        else -> name
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 72.dp, end = Spacing.l),
    ) {
        Text("\u2022", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = Spacing.s).clearAndSetSemantics { })
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (showButton) {
            TextButton(onClick = onUpdate, enabled = enabled, modifier = Modifier.semantics { contentDescription = updateDescription }) {
                Text(stringResource(R.string.regions_update))
            }
        }
    }
}
