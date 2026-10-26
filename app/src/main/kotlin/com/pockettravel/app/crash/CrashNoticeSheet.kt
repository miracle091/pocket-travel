package com.pockettravel.app.crash

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.app.R
import com.pockettravel.core.ui.Spacing

/** Foglio al lancio dopo una chiusura inattesa; non compare se non c'è un rapporto in attesa. */
@Composable
fun CrashNoticeHost(viewModel: CrashNoticeViewModel = hiltViewModel()) {
    val notice by viewModel.state.collectAsStateWithLifecycle()
    notice?.let { CrashNoticeSheet(it, onDismiss = viewModel::dismiss, onDownload = viewModel::downloadProposed) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CrashNoticeSheet(notice: CrashNoticeState, onDismiss: () -> Unit, onDownload: (String) -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl).padding(bottom = Spacing.xxl)) {
            Text(
                text = stringResource(R.string.crash_notice_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            notice.proposal?.let { proposal ->
                TextButton(onClick = { onDownload(proposal.proposedId) }) {
                    Text(stringResource(R.string.crash_notice_smaller_model, proposal.loadedName, proposal.proposedName))
                }
            }
            SelectionContainer {
                Text(
                    text = notice.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.m)
                        .heightIn(max = REPORT_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                )
            }
            Button(onClick = { shareReport(context, notice.text) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.crash_notice_share))
            }
            OutlinedButton(onClick = { openIssue(context, notice.issueUrl) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.crash_notice_github))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.crash_notice_close))
            }
        }
    }
}

private val REPORT_MAX_HEIGHT = 240.dp

// Foglio di condivisione di sistema: il rapporto parte solo se l'utente sceglie dove inviarlo.
private fun shareReport(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun openIssue(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Nessun browser: resta la condivisione del testo.
    }
}
