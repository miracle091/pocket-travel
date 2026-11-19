package com.pockettravel.feature.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.R as UiR

/**
 * La stella accanto alla destinazione: piena se e' tra i preferiti (toccandola si toglie), vuota altrimenti (si chiede il
 * nome, libero, con "Alloggio" a un tocco).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FavoriteToggle(place: NavigationPlace, favorite: Boolean, onSave: (name: String) -> Unit, onRemove: () -> Unit) {
    var naming by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { if (favorite) onRemove() else naming = true }, modifier = Modifier.size(40.dp)) {
        Icon(
            ImageVector.vectorResource(if (favorite) UiR.drawable.ms_star_fill else UiR.drawable.ms_star),
            contentDescription = stringResource(if (favorite) R.string.planner_favorite_remove else R.string.planner_favorite_add),
            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (naming) {
        var name by rememberSaveable(place) { mutableStateOf(place.name) }
        val save = {
            naming = false
            onSave(name)
        }
        AlertDialog(
            onDismissRequest = { naming = false },
            icon = { Icon(ImageVector.vectorResource(UiR.drawable.ms_star), contentDescription = null) },
            title = { Text(stringResource(R.string.planner_favorite_add)) },
            text = {
                Column {
                    TextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.planner_favorite_name)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val lodging = stringResource(R.string.planner_favorite_lodging)
                    AssistChip(onClick = { name = lodging }, label = { Text(lodging) }, modifier = Modifier.padding(top = Spacing.s))
                }
            },
            confirmButton = { TextButton(onClick = save, enabled = name.isNotBlank()) { Text(stringResource(R.string.planner_favorite_save)) } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}
