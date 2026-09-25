package com.pockettravel.app.more

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.app.R
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.Spacing
import com.pockettravel.feature.map.UsageMode
import com.pockettravel.feature.map.UsageModeOptions

// Terza destinazione principale ("Altro"): raccoglie le voci di servizio che prima stavano nella
// sezione "App" del menu laterale, rimosso col passaggio alla barra di navigazione M3.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onOpenSources: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenTutorial: () -> Unit,
    onOpenLicenses: () -> Unit,
    viewModel: MoreViewModel = hiltViewModel(),
) {
    val useDynamicColor by viewModel.useDynamicColor.collectAsStateWithLifecycle()
    val usageMode by viewModel.usageMode.collectAsStateWithLifecycle()
    var showUsageModes by rememberSaveable { mutableStateOf(false) }
    // Dentro NavigationSuiteScaffold: gli inset di sistema li gestiscono la barra/rail e la top app bar,
    // applicarli anche qui lascerebbe una fascia vuota sopra la barra di navigazione.
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.more_title)) }) },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).verticalScroll(rememberScrollState())) {
            MoreItem(
                ImageVector.vectorResource((usageMode ?: UsageMode.A_PIEDI).icon),
                stringResource(R.string.more_usage_mode),
                usageMode?.let { stringResource(it.label) } ?: stringResource(R.string.more_usage_mode_none),
            ) { showUsageModes = true }
            MoreItem(AppIcons.OfficialAuthority, stringResource(R.string.more_sources), stringResource(R.string.more_sources_subtitle), onOpenSources)
            MoreItem(AppIcons.Storage, stringResource(R.string.more_storage), stringResource(R.string.more_storage_subtitle), onOpenStorage)
            MoreItem(AppIcons.Tutorial, stringResource(R.string.more_tutorial), null, onOpenTutorial)
            // Il dynamic color esiste solo da Android 12: sotto, l'interruttore non avrebbe effetto.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.more_dynamic_color)) },
                    supportingContent = { Text(stringResource(R.string.more_dynamic_color_subtitle)) },
                    leadingContent = { Icon(imageVector = AppIcons.Palette, contentDescription = null) },
                    trailingContent = { Switch(checked = useDynamicColor, onCheckedChange = null) },
                    modifier = Modifier.toggleable(
                        value = useDynamicColor,
                        role = Role.Switch,
                        onValueChange = viewModel::setUseDynamicColor,
                    ),
                )
            }
            MoreItem(AppIcons.Licenses, stringResource(R.string.more_licenses), stringResource(R.string.more_licenses_subtitle), onOpenLicenses)
        }
    }

    // Scegliere una modalita' riporta i filtri della mappa ai suoi predefiniti: lo dice il testo del foglio.
    if (showUsageModes) {
        // Aperto per intero e scorrevole: 7 voci non stanno a mezza altezza, ne' con i caratteri grandi.
        ModalBottomSheet(onDismissRequest = { showUsageModes = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Spacing.l)) {
                Text(
                    text = stringResource(R.string.more_usage_mode),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = Spacing.xl).semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.more_usage_mode_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s),
                )
                UsageModeOptions(
                    selected = usageMode,
                    onSelect = { viewModel.setUsageMode(it); showUsageModes = false },
                    modifier = Modifier.padding(horizontal = Spacing.s),
                )
            }
        }
    }
}

@Composable
private fun MoreItem(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
