package com.pockettravel.app.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.pockettravel.app.R
import com.pockettravel.core.ui.AppIcons

// Quarta destinazione principale ("Altro"): le Impostazioni (lingua, nazionalita', aspetto, Navigatore) e
// le voci di servizio (Fonti ufficiali, Spazio, Tutorial, Licenze).
@Composable
fun MoreScreen(
    onOpenSettings: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenTutorial: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    // Senza barra del titolo: la voce "Altro" della barra in basso dice gia' dove si e'. Dentro NavigationSuiteScaffold gli
    // inset in basso li gestisce la barra: qui solo quello della barra di stato.
    Scaffold(contentWindowInsets = WindowInsets(0)) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).windowInsetsPadding(WindowInsets.statusBars).verticalScroll(rememberScrollState())) {
            MoreItem(AppIcons.Settings, stringResource(R.string.settings_title), stringResource(R.string.more_settings_subtitle), onOpenSettings)
            MoreItem(AppIcons.OfficialAuthority, stringResource(R.string.more_sources), stringResource(R.string.more_sources_subtitle), onOpenSources)
            MoreItem(AppIcons.Storage, stringResource(R.string.more_storage), stringResource(R.string.more_storage_subtitle), onOpenStorage)
            MoreItem(AppIcons.Tutorial, stringResource(R.string.more_tutorial), stringResource(R.string.more_tutorial_subtitle), onOpenTutorial)
            MoreItem(AppIcons.Licenses, stringResource(R.string.more_licenses), stringResource(R.string.more_licenses_subtitle), onOpenLicenses)
        }
    }
}

@Composable
private fun MoreItem(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
        content = { Text(title) },
    )
}
