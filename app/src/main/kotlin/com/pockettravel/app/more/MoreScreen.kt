package com.pockettravel.app.more

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.app.R
import com.pockettravel.core.ui.AppIcons

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
    // Dentro NavigationSuiteScaffold: gli inset di sistema li gestiscono la barra/rail e la top app bar,
    // applicarli anche qui lascerebbe una fascia vuota sopra la barra di navigazione.
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.more_title)) }) },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).verticalScroll(rememberScrollState())) {
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
