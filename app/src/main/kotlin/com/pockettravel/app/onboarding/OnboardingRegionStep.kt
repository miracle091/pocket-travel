package com.pockettravel.app.onboarding

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.pockettravel.app.R
import com.pockettravel.app.regions.RegionListViewModel
import com.pockettravel.app.regions.RegionStatus
import com.pockettravel.app.regions.RegionUiItem
import com.pockettravel.app.regions.label
import com.pockettravel.core.data.PackageKind
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.CountryFlag
import com.pockettravel.core.ui.PocketTravelLoadingIndicator
import com.pockettravel.core.ui.Spacing

/**
 * Passo "Scarica le regioni" del primo avvio: regioni consigliate (proprio paese e vicini), ricerca,
 * piu' regioni insieme e, per ciascuna, i pacchetti da scaricare. Facoltativo: si puo' fare dopo
 * dalla schermata Regioni.
 */
@Composable
internal fun OnboardingRegionStep(onboardingViewModel: OnboardingViewModel, viewModel: RegionListViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val nationality by onboardingViewModel.nationality.collectAsStateWithLifecycle()
    val wantsDirections by onboardingViewModel.wantsDirections.collectAsStateWithLifecycle()
    // Regione scelta -> pacchetti da scaricare; resta dopo una rotazione.
    val selection = rememberSaveable(saver = selectionSaver) { mutableStateMapOf<String, Set<PackageKind>>() }
    val searching = uiState.query.isNotBlank()
    // Senza nazionalita' (per esempio lingua dell'app scelta senza paese) il paese delle impostazioni di
    // sistema: da Android 13, con una lingua per l'app, lo da' solo LocaleManager.systemLocales.
    val context = LocalContext.current
    val country = nationality ?: remember { systemCountry(context) }
    val recommended = remember(uiState.items, country) { recommendedRegions(uiState.items, country) }
    val others = if (searching) uiState.items else uiState.items - recommended.toSet()

    Column(modifier = Modifier.fillMaxSize()) {
        StepHeader(
            icon = AppIcons.World,
            title = stringResource(R.string.onboarding_region_title),
            body = stringResource(R.string.onboarding_region_body),
        )
        // Testo nel TextFieldState locale e passato al ViewModel, come nella schermata Regioni: legato
        // direttamente allo stato del ViewModel (aggiornato in modo asincrono) il campo perdeva caratteri.
        val textFieldState = rememberTextFieldState(uiState.query)
        LaunchedEffect(textFieldState) {
            snapshotFlow { textFieldState.text.toString() }.collect(viewModel::onQueryChange)
        }
        OutlinedTextField(
            state = textFieldState,
            leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.onboarding_region_search)) },
            lineLimits = TextFieldLineLimits.SingleLine,
            modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.s),
        )
        when {
            uiState.isLoading && uiState.items.isEmpty() -> PocketTravelLoadingIndicator()
            uiState.loadError != null -> Text(text = stringResource(uiState.loadError!!), color = MaterialTheme.colorScheme.error)
            else -> Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.weight(1f),
            ) {
                LazyColumn {
                    val row: @Composable (RegionUiItem) -> Unit = { item ->
                        OnboardingRegionRow(
                            item = item,
                            selectedKinds = selection[item.regionId],
                            defaultKinds = defaultPackageChoice(item, wantsDirections),
                            onSelectionChange = { kinds -> if (kinds.isNullOrEmpty()) selection.remove(item.regionId) else selection[item.regionId] = kinds },
                            viewModel = viewModel,
                        )
                    }
                    if (!searching && recommended.isNotEmpty()) {
                        item(key = "header-recommended") { SectionHeader(stringResource(R.string.onboarding_region_recommended)) }
                        items(recommended, key = { "recommended-${it.regionId}" }) { row(it) }
                        item(key = "header-all") { SectionHeader(stringResource(R.string.onboarding_region_all)) }
                    }
                    items(others, key = { it.regionId }) { row(it) }
                }
            }
        }
        if (selection.isNotEmpty()) {
            val totalBytes = selection.entries.sumOf { (id, kinds) -> uiState.items.firstOrNull { it.regionId == id }?.let { downloadBytes(it, kinds) } ?: 0L }
            Button(
                onClick = {
                    selection.forEach { (id, kinds) -> viewModel.downloadKinds(id, kinds) }
                    selection.clear()
                },
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
            ) {
                Text(pluralStringResource(R.plurals.onboarding_region_download, selection.size, selection.size, Formatter.formatShortFileSize(context, totalBytes)))
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Spacing.l, top = Spacing.m, bottom = Spacing.xs).semantics { heading() },
    )
}

// Una regione: gia' scaricata o in download (solo stato), altrimenti da spuntare; spuntata, sotto
// compaiono i pacchetti, con quelli di "Scarica" gia' scelti.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnboardingRegionRow(
    item: RegionUiItem,
    selectedKinds: Set<PackageKind>?,
    defaultKinds: Set<PackageKind>,
    onSelectionChange: (Set<PackageKind>?) -> Unit,
    viewModel: RegionListViewModel,
) {
    val context = LocalContext.current
    val work by remember(item.regionId) { viewModel.observeDownloadProgress(item.regionId) }.collectAsStateWithLifecycle(null)
    val downloading = work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED
    val selectable = item.status == RegionStatus.NOT_INSTALLED && !downloading
    val kinds = selectedKinds ?: defaultKinds
    val size = Formatter.formatShortFileSize(context, downloadBytes(item, kinds))
    Column {
        ListItem(
            leadingContent = { CountryFlag(item.countryCode, size = 32.dp) { Icon(AppIcons.World, contentDescription = null) } },
            headlineContent = { Text(item.displayName) },
            supportingContent = {
                Text(
                    when {
                        downloading -> stringResource(R.string.onboarding_region_downloading)
                        item.status != RegionStatus.NOT_INSTALLED -> stringResource(R.string.onboarding_region_installed)
                        else -> size
                    },
                )
            },
            trailingContent = if (selectable) {
                { Checkbox(checked = selectedKinds != null, onCheckedChange = null) }
            } else null,
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = if (selectable) {
                Modifier.toggleable(value = selectedKinds != null, role = Role.Checkbox) { checked ->
                    onSelectionChange(if (checked) defaultKinds else null)
                }
            } else Modifier,
        )
        if (downloading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l))
        if (selectedKinds != null) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.s),
            ) {
                item.packages.forEach { pkg ->
                    val label = stringResource(pkg.kind.label())
                    FilterChip(
                        selected = pkg.kind in selectedKinds,
                        onClick = { onSelectionChange(if (pkg.kind in selectedKinds) selectedKinds - pkg.kind else selectedKinds + pkg.kind) },
                        // La mappa non ha una dimensione nota prima dell'estrazione sul telefono.
                        label = { Text(if (pkg.downloadBytes > 0) "$label · ${Formatter.formatShortFileSize(context, pkg.downloadBytes)}" else label) },
                        leadingIcon = if (pkg.kind in selectedKinds) {
                            { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null,
                    )
                }
            }
        }
    }
}

private fun systemCountry(context: Context): String? {
    val locales = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.getSystemService(LocaleManager::class.java).systemLocales
    } else {
        Resources.getSystem().configuration.locales
    }
    return locales.takeIf { !it.isEmpty }?.get(0)?.country?.takeIf { it.length == 2 }
}

private val selectionSaver = listSaver<SnapshotStateMap<String, Set<PackageKind>>, String>(
    save = { map -> map.flatMap { (id, kinds) -> listOf(id, kinds.joinToString(",") { it.name }) } },
    restore = { saved ->
        mutableStateMapOf<String, Set<PackageKind>>().apply {
            saved.chunked(2).forEach { (id, kinds) -> put(id, kinds.split(',').filter { it.isNotEmpty() }.mapTo(mutableSetOf(), PackageKind::valueOf)) }
        }
    },
)
