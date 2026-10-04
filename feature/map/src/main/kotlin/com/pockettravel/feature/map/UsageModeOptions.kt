package com.pockettravel.feature.map

import com.pockettravel.core.ui.AppIcons
import androidx.compose.material3.Switch
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.Manifest
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.semantics.Role
import com.pockettravel.core.ui.R as UiR
import com.pockettravel.core.ui.Spacing

/**
 * Le modalita' d'uso come elenco a scelta multipla (onboarding e Altro), piu' la casella
 * "In sedia a rotelle" che si somma a qualsiasi modalita'.
 */
@Composable
fun UsageModeOptions(
    selected: Set<UsageMode>,
    onSelectedChange: (Set<UsageMode>) -> Unit,
    accessible: Boolean,
    onAccessibleChange: (Boolean) -> Unit,
    directions: Boolean,
    onDirectionsChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Accendendo "Indicazioni" si chiede subito il permesso di posizione, solo in primo piano;
    // chi rifiuta lo concede dopo dalla schermata di navigazione.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.usage_mode_multiple),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
        )
        Column {
            // "Escursionismo" nascosto finche' non ha contenuti suoi (cammini a tappe): resta solo per
            // chi l'aveva gia' scelto, per non lasciarlo senza modalita' selezionata.
            UsageMode.entries.filter { it != UsageMode.ESCURSIONISMO || it in selected }.forEach { mode ->
                val checked = mode in selected
                ListItem(
                    leadingContent = { Icon(ImageVector.vectorResource(mode.icon), contentDescription = null) },
                    trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.toggleable(value = checked, role = Role.Checkbox) { on ->
                        onSelectedChange(if (on) selected + mode else selected - mode)
                    },
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
        ListItem(
            leadingContent = { Icon(AppIcons.Route, contentDescription = null) },
            trailingContent = { Switch(checked = directions, onCheckedChange = null) },
            supportingContent = { Text(stringResource(R.string.usage_mode_directions_body)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.toggleable(value = directions, role = Role.Switch) { wants ->
                onDirectionsChange(wants)
                if (wants) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            },
            content = { Text(stringResource(R.string.usage_mode_directions)) },
        )
    }
}
