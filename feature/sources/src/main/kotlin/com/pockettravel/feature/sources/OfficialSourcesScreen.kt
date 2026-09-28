package com.pockettravel.feature.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pockettravel.core.data.OfficialSource
import com.pockettravel.core.data.globalOfficialSources
import com.pockettravel.core.data.nationalOfficialSources
import com.pockettravel.core.ui.countryName
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.R as UiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficialSourcesScreen(nationality: String?, onBack: () -> Unit, onOpenSource: (url: String, title: String) -> Unit) {
    val national = nationalOfficialSources(nationality)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sources_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = AppIcons.Back, contentDescription = stringResource(UiR.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        // Scorrevole: le fonti non stanno tutte in uno schermo, con i caratteri grandi nemmeno la meta'.
        Column(modifier = Modifier.padding(innerPadding).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.l, vertical = Spacing.s)) {
            Text(
                text = stringResource(R.string.sources_external),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.s),
            )
            // Prima quelle del paese di chi viaggia (NationalityPreferences), poi le internazionali, sempre
            // presenti; senza fonti per quel paese lo dice invece di mostrare quelle di un altro.
            if (nationality != null) {
                SectionTitle(stringResource(R.string.sources_national, countryName(nationality)))
                if (national.isEmpty()) {
                    Text(
                        text = stringResource(R.string.sources_national_none),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.s),
                    )
                } else {
                    SourcesCard(national, onOpenSource)
                }
            }
            SectionTitle(stringResource(R.string.sources_global))
            SourcesCard(globalOfficialSources, onOpenSource)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Spacing.s, end = Spacing.s, top = Spacing.l, bottom = Spacing.s).semantics { heading() },
    )
}

@Composable
private fun SourcesCard(sources: List<OfficialSource>, onOpenSource: (url: String, title: String) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            sources.forEachIndexed { index, source ->
                OfficialSourceRow(source, onClick = { onOpenSource(source.url, source.name) })
                if (index < sources.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            }
        }
    }
}

@Composable
private fun OfficialSourceRow(source: OfficialSource, onClick: () -> Unit) {
    ListItem(
        supportingContent = { Text(source.description) },
        leadingContent = { Icon(AppIcons.OfficialAuthority, contentDescription = null) },
        trailingContent = { Icon(AppIcons.OpenExternal, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
        content = { Text(source.name) },
    )
}
