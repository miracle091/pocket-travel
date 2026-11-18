package com.pockettravel.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.pockettravel.core.data.GuideTerm

/**
 * Foglio di un termine locale toccato nella guida o in una risposta dell'assistente: la spiegazione dalla voce di
 * Wikipedia, la voce e la domanda all'assistente. Niente senza un termine aperto ([term] null).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideTermSheet(
    term: GuideTerm?,
    onDismiss: () -> Unit,
    onOpenSource: (url: String, title: String) -> Unit,
    // null senza assistente disponibile (nessun modello scaricato né chiave API).
    onAskAssistant: ((question: String) -> Unit)?,
) {
    if (term == null) return
    // Subito aperto per intero: a metà altezza il fondo del foglio, con «Chiedi all'assistente», restava sotto il
    // bordo dello schermo.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xl)
                .semantics { paneTitle = term.term },
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text(term.term, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(term.summary, style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { onOpenSource(term.sourceUrl, term.title) }) {
                Text(stringResource(R.string.guide_term_source, term.title))
            }
            if (onAskAssistant != null) {
                val question = stringResource(R.string.guide_term_question, term.term)
                Button(
                    onClick = {
                        onDismiss()
                        onAskAssistant(question)
                    },
                ) {
                    Text(stringResource(R.string.guide_term_ask))
                }
            }
        }
    }
}

/**
 * Il termine del foglio aperto, per rememberSaveable: il foglio resta aperto quando Android ricrea l'activity
 * (rotazione, dimensione del testo).
 */
val GuideTermSaver = Saver<GuideTerm?, ArrayList<String>>(
    save = { term -> term?.let { arrayListOf(it.regionId, it.term, it.title, it.summary, it.sourceUrl).apply { addAll(it.aliases) } } },
    restore = { GuideTerm(it[0], it[1], it.drop(SAVED_FIELDS), it[2], it[3], it[4]) },
)

private const val SAVED_FIELDS = 5
