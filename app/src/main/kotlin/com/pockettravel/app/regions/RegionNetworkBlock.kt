package com.pockettravel.app.regions

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.pockettravel.core.sync.NetworkBlock
import com.pockettravel.core.sync.RegionPackageDownloadWorker
import com.pockettravel.core.ui.NetworkBlockSettingsButton
import com.pockettravel.core.ui.Spacing
import com.pockettravel.core.ui.R as UiR

/** Il messaggio di rete bloccata sotto la riga di una nazione, col pulsante per le impostazioni: elenco e Aggiornamenti. */
@Composable
internal fun NetworkBlockNotice(@StringRes message: Int) {
    Text(
        text = stringResource(message),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 72.dp, end = Spacing.l),
    )
    Row(modifier = Modifier.padding(start = 64.dp)) { NetworkBlockSettingsButton(message) }
}

/** Il messaggio di rete bloccata se il lavoro è fallito perché Android blocca la rete dell'app, altrimenti null. */
@StringRes
internal fun WorkInfo?.blockMessage(): Int? {
    if (this?.state != WorkInfo.State.FAILED) return null
    return when (outputData.getString(RegionPackageDownloadWorker.KEY_FAILURE_REASON)) {
        RegionPackageDownloadWorker.FAILURE_REASON_DATA_SAVER -> UiR.string.network_blocked_data_saver
        RegionPackageDownloadWorker.FAILURE_REASON_NETWORK_BLOCKED -> UiR.string.network_blocked_suspected
        else -> null
    }
}

/** Il messaggio per l'utente di un blocco della rete dell'app; null se non c'è blocco. */
@StringRes
internal fun NetworkBlock.message(): Int? = when (this) {
    NetworkBlock.NONE -> null
    NetworkBlock.DATA_SAVER -> UiR.string.network_blocked_data_saver
    NetworkBlock.SUSPECTED -> UiR.string.network_blocked_suspected
}
