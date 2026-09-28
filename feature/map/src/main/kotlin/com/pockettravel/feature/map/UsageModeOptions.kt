package com.pockettravel.feature.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import com.pockettravel.core.ui.R as UiR

/**
 * Le modalita' d'uso come elenco a scelta singola (onboarding e Altro), piu' la casella
 * "Con disabilita'" che si somma a qualsiasi modalita'.
 */
@Composable
fun UsageModeOptions(
    selected: UsageMode?,
    onSelect: (UsageMode) -> Unit,
    accessible: Boolean,
    onAccessibleChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Column(modifier = Modifier.selectableGroup()) {
            UsageMode.entries.forEach { mode ->
                ListItem(
                    leadingContent = { Icon(ImageVector.vectorResource(mode.icon), contentDescription = null) },
                    trailingContent = { RadioButton(selected = mode == selected, onClick = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.selectable(selected = mode == selected, role = Role.RadioButton) { onSelect(mode) },
                    content = { Text(stringResource(mode.label)) },
                )
            }
        }
        HorizontalDivider()
        ListItem(
            leadingContent = { Icon(ImageVector.vectorResource(UiR.drawable.ms_accessible), contentDescription = null) },
            trailingContent = { Checkbox(checked = accessible, onCheckedChange = null) },
            supportingContent = { Text(stringResource(R.string.usage_mode_wheelchair_body)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.toggleable(value = accessible, role = Role.Checkbox, onValueChange = onAccessibleChange),
            content = { Text(stringResource(R.string.usage_mode_wheelchair)) },
        )
    }
}
