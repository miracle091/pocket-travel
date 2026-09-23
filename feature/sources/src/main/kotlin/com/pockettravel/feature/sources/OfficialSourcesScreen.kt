package com.pockettravel.feature.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.pockettravel.core.data.OfficialSource
import com.pockettravel.core.data.officialSourcesRegistry
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.R as UiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficialSourcesScreen(onBack: () -> Unit, onOpenSource: (url: String, title: String) -> Unit) {
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
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().padding(innerPadding).padding(horizontal = Spacing.l, vertical = Spacing.s),
        ) {
            Column {
                officialSourcesRegistry.forEachIndexed { index, source ->
                    OfficialSourceRow(source, onClick = { onOpenSource(source.url, source.name) })
                    if (index < officialSourcesRegistry.lastIndex) HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                }
            }
        }
    }
}

@Composable
private fun OfficialSourceRow(source: OfficialSource, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(source.name) },
        supportingContent = { Text(stringResource(R.string.sources_external)) },
        leadingContent = { Icon(AppIcons.OfficialAuthority, contentDescription = null) },
        trailingContent = { Icon(AppIcons.OpenExternal, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
