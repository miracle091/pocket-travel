package com.pockettravel.feature.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role

/** Le modalita' d'uso come elenco a scelta singola (onboarding e Altro). */
@Composable
fun UsageModeOptions(selected: UsageMode?, onSelect: (UsageMode) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.selectableGroup()) {
        UsageMode.entries.forEach { mode ->
            ListItem(
                headlineContent = { Text(stringResource(mode.label)) },
                leadingContent = { Icon(ImageVector.vectorResource(mode.icon), contentDescription = null) },
                trailingContent = { RadioButton(selected = mode == selected, onClick = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.selectable(selected = mode == selected, role = Role.RadioButton) { onSelect(mode) },
            )
        }
    }
}
