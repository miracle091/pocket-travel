package com.pockettravel.app.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
) {
    // Dentro NavigationSuiteScaffold: gli inset di sistema li gestiscono la barra/rail e la top app bar,
    // applicarli anche qui lascerebbe una fascia vuota sopra la barra di navigazione.
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("Altro") }) },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).verticalScroll(rememberScrollState())) {
            MoreItem(AppIcons.OfficialAuthority, "Fonti ufficiali", "Ministeri, ambasciate e OMS", onOpenSources)
            MoreItem(AppIcons.Storage, "Spazio di archiviazione", "Regioni e modelli IA scaricati", onOpenStorage)
            MoreItem(AppIcons.Tutorial, "Rivedi tutorial", null, onOpenTutorial)
            MoreItem(AppIcons.Licenses, "Licenze", "Componenti e dati di terze parti", onOpenLicenses)
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
