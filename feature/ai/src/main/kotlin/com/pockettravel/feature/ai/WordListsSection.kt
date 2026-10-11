package com.pockettravel.feature.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.ui.Spacing

/** Sezione del task manager con l'interruttore delle liste di parole per categoria nella ricerca (WordListsOverride). */
@Composable
internal fun WordListsSection(viewModel: LlmTaskManagerViewModel) {
    val enabled by viewModel.wordLists.collectAsStateWithLifecycle()
    SectionTitle(R.string.ai_taskmgr_context)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = enabled, role = Role.Switch, onValueChange = viewModel::setWordLists),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = Spacing.s)) {
            Text(stringResource(R.string.ai_taskmgr_word_lists), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.ai_taskmgr_word_lists_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = null)
    }
}
