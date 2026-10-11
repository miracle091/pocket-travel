package com.pockettravel.app.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.pockettravel.app.R
import com.pockettravel.core.ui.AppIcons

// Quarta destinazione principale ("Altro"): le Impostazioni (lingua, nazionalita', aspetto, Navigatore) e
// le voci di servizio (Aggiornamenti, con il numero di quelli disponibili, Fonti ufficiali, Spazio, Tutorial, Licenze).
@Composable
fun MoreScreen(
    // Aggiornamenti disponibili in tutto (app, nazioni, guide, modello IA): 0 = nessun badge.
    updatesCount: Int,
    onOpenUpdates: () -> Unit,
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
            MoreItem(
                AppIcons.Refresh, stringResource(R.string.more_updates), stringResource(R.string.more_updates_subtitle), onOpenUpdates,
                badge = updatesCount,
            )
            MoreItem(AppIcons.Settings, stringResource(R.string.settings_title), stringResource(R.string.more_settings_subtitle), onOpenSettings)
            MoreItem(AppIcons.OfficialAuthority, stringResource(R.string.more_sources), stringResource(R.string.more_sources_subtitle), onOpenSources)
            MoreItem(AppIcons.Storage, stringResource(R.string.more_storage), stringResource(R.string.more_storage_subtitle), onOpenStorage)
            MoreItem(AppIcons.Tutorial, stringResource(R.string.more_tutorial), stringResource(R.string.more_tutorial_subtitle), onOpenTutorial)
            MoreItem(AppIcons.Licenses, stringResource(R.string.more_licenses), stringResource(R.string.more_licenses_subtitle), onOpenLicenses)
        }
    }
}

@Composable
private fun MoreItem(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit, badge: Int = 0) {
    val badgeDescription = pluralStringResource(R.plurals.updates_available_count, badge, badge)
    ListItem(
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        trailingContent = if (badge > 0) {
            { Badge(modifier = Modifier.semantics { contentDescription = badgeDescription }) { Text(badge.toString()) } }
        } else {
            null
        },
        modifier = Modifier.clickable(onClick = onClick),
        content = { Text(title) },
    )
}
