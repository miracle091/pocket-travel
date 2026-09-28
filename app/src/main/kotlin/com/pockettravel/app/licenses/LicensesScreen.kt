package com.pockettravel.app.licenses

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.pockettravel.app.R
import com.pockettravel.core.sync.AddressGridAttribution
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.R as UiR

// Mostra thirdPartyLicenses (LicenseData.kt): solo licenze di terze parti realmente in uso,
// compilate nell'app invece che lette da un asset a runtime. In coda, le fonti dei civici a griglia
// (address-grid-plan.md "App" 6), lette da address-grid.json quando il manifest le offre.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LicensesScreen(onBack: () -> Unit, viewModel: LicensesViewModel = hiltViewModel()) {
    val addressAttributions by viewModel.addressAttributions.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.licenses_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = AppIcons.Back, contentDescription = stringResource(UiR.string.back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = innerPadding) {
            items(thirdPartyLicenses, key = { it.component }) { entry ->
                ListItem(
                    headlineContent = { Text(entry.component) },
                    supportingContent = {
                        Text(
                            buildString {
                                append(stringResource(R.string.licenses_license, entry.license))
                                entry.note?.let { append("\n").append(it) }
                            },
                        )
                    },
                )
                HorizontalDivider(modifier = Modifier.padding(start = Spacing.l))
            }
            if (addressAttributions.isNotEmpty()) {
                item(key = "address-grid-sources-title") {
                    Text(
                        text = stringResource(R.string.licenses_address_sources_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = Spacing.l, top = Spacing.l, bottom = Spacing.s),
                    )
                }
                items(addressAttributions, key = { "address-grid-source-${it.source}" }) { attribution ->
                    AddressAttributionRow(attribution)
                    HorizontalDivider(modifier = Modifier.padding(start = Spacing.l))
                }
            }
        }
    }
}

@Composable
private fun AddressAttributionRow(attribution: AddressGridAttribution) {
    ListItem(
        headlineContent = { Text(attribution.source) },
        supportingContent = { Text(stringResource(R.string.licenses_license, attribution.license)) },
    )
}
