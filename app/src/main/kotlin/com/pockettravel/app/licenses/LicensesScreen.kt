package com.pockettravel.app.licenses

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.aboutlibraries.entity.Library
import com.pockettravel.app.R
import com.pockettravel.core.sync.AddressGridAttribution
import com.pockettravel.core.sync.TransitFeed
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.R as UiR

// Elenco di LicensesViewModel.libraries diviso in software, modelli e dati (LicenseData.kt); ogni voce apre la
// pagina del componente con avviso di copyright e testo completo della licenza. In coda, le fonti dei civici a
// griglia, lette da address-grid.json quando il catalogo le offre, e le reti dei mezzi pubblici di transit.json
// con il link alla loro licenza.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LicensesScreen(viewModel: LicensesViewModel, onBack: () -> Unit, onOpenLibrary: (String) -> Unit) {
    val libraries by viewModel.libraries.collectAsStateWithLifecycle()
    val addressAttributions by viewModel.addressAttributions.collectAsStateWithLifecycle()
    val transitFeeds by viewModel.transitFeeds.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val groups = libraries.groupBy { it.group() }.mapValues { (_, group) -> group.sortedBy { it.displayName(context).lowercase() } }
    LicensesScaffold(title = stringResource(R.string.licenses_title), onBack = onBack) { innerPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = innerPadding) {
            LicenseGroup.entries.forEach { group ->
                val members = groups[group].orEmpty()
                if (members.isNotEmpty()) {
                    sectionTitle(key = "group-${group.name}", title = group.title)
                    items(members, key = { it.uniqueId }) { library ->
                        LibraryRow(library, onClick = { onOpenLibrary(library.uniqueId) })
                        HorizontalDivider(modifier = Modifier.padding(start = Spacing.l))
                    }
                }
            }
            if (addressAttributions.isNotEmpty()) {
                sectionTitle(key = "address-grid-sources-title", title = R.string.licenses_address_sources_title)
                items(addressAttributions, key = { "address-grid-source-${it.source}" }) { attribution ->
                    AddressAttributionRow(attribution)
                    HorizontalDivider(modifier = Modifier.padding(start = Spacing.l))
                }
            }
            if (transitFeeds.isNotEmpty()) {
                sectionTitle(key = "transit-sources-title", title = R.string.licenses_transit_sources_title)
                items(transitFeeds, key = { "transit-source-${it.id}" }) { feed ->
                    TransitFeedRow(feed, onOpenLicense = { uriHandler.openUri(it) })
                    HorizontalDivider(modifier = Modifier.padding(start = Spacing.l))
                }
            }
        }
    }
}

/** Pagina di un componente: versione, nota, e per ogni licenza l'avviso di copyright e il testo, leggibili senza rete. */
@Composable
fun LicenseScreen(viewModel: LicensesViewModel, uniqueId: String, onBack: () -> Unit) {
    val libraries by viewModel.libraries.collectAsStateWithLifecycle()
    val library = libraries.firstOrNull { it.uniqueId == uniqueId }
    val uriHandler = LocalUriHandler.current
    LicensesScaffold(title = library?.displayName(LocalContext.current).orEmpty(), onBack = onBack) { innerPadding ->
        if (library == null) return@LicensesScaffold
        val label = licenseLabels[library.uniqueId]
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(horizontal = Spacing.l, vertical = Spacing.s),
            ) {
                library.artifactVersion?.let { Text(stringResource(R.string.licenses_version, it), style = MaterialTheme.typography.bodyMedium) }
                label?.note?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.s)) }
                library.shownLicenses().forEach { license ->
                    Text(
                        text = license.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = Spacing.l, bottom = Spacing.s).semantics { heading() },
                    )
                    license.licenseContent?.let { content ->
                        Text(remember(content) { reflowLicenseText(content.trim()) }, style = MaterialTheme.typography.bodySmall)
                    }
                    license.url?.let { url -> TextButton(onClick = { uriHandler.openUri(url) }) { Text(stringResource(R.string.licenses_link)) } }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LicensesScaffold(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = AppIcons.Back, contentDescription = stringResource(UiR.string.back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        content = content,
    )
}

private fun LazyListScope.sectionTitle(key: String, @StringRes title: Int) {
    item(key = key) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = Spacing.l, top = Spacing.l, bottom = Spacing.s).semantics { heading() },
        )
    }
}

@Composable
private fun LibraryRow(library: Library, onClick: () -> Unit) {
    val note = licenseLabels[library.uniqueId]?.note
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        supportingContent = {
            Text(
                buildString {
                    val licenses = licenseLabels[library.uniqueId]?.license?.let { stringResource(it) } ?: library.shownLicenses().joinToString { it.name }
                    append(stringResource(R.string.licenses_license, licenses))
                    note?.let { append("\n").append(stringResource(it)) }
                },
            )
        },
        content = { Text(library.displayName(LocalContext.current)) },
    )
}

@Composable
private fun TransitFeedRow(feed: TransitFeed, onOpenLicense: (String) -> Unit) {
    ListItem(
        supportingContent = {
            Column {
                Text(feed.attribution)
                Text(stringResource(R.string.licenses_license, feed.license))
                feed.licenseUrl?.let { url -> TextButton(onClick = { onOpenLicense(url) }) { Text(stringResource(R.string.licenses_link)) } }
            }
        },
        content = { Text(feed.name) },
    )
}

@Composable
private fun AddressAttributionRow(attribution: AddressGridAttribution) {
    ListItem(
        supportingContent = { Text(stringResource(R.string.licenses_license, attribution.license)) },
        content = { Text(attribution.source) },
    )
}
