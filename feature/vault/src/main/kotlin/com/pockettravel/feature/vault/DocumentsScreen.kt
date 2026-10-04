package com.pockettravel.feature.vault

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.Spacing

/**
 * Scheda Documenti della barra principale: in alto il selettore Documenti | Note, sotto la cassaforte dei documenti
 * (con lo sblocco biometrico) o le note personali (senza). Lasciare i documenti per le note chiude la cassaforte,
 * come uscire dalla schermata.
 */
@Composable
fun DocumentsScreen() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s)) {
            SegmentedButton(
                selected = selected == 0,
                onClick = { selected = 0 },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = { Icon(AppIcons.Passport, contentDescription = null) },
                label = { Text(stringResource(R.string.vault_title)) },
            )
            SegmentedButton(
                selected = selected == 1,
                onClick = { selected = 1 },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = { Icon(AppIcons.Notes, contentDescription = null) },
                label = { Text(stringResource(R.string.notes_title)) },
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            if (selected == 0) VaultScreen() else NotesScreen()
        }
    }
}
