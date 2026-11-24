package com.pockettravel.core.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri

/**
 * Apre la schermata di Android dove si sblocca la rete dell'app: quella del Risparmio dati se [dataSaver], altrimenti i
 * dettagli dell'app. Se manca (alcuni telefoni) ripiega sui dettagli dell'app e poi sulle impostazioni generali.
 */
fun openNetworkSettings(context: Context, dataSaver: Boolean) {
    val appUri = "package:${context.packageName}".toUri()
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri)
    val attempts = buildList {
        if (dataSaver) add(Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, appUri))
        add(appDetails)
        add(Intent(Settings.ACTION_SETTINGS))
    }
    for (intent in attempts) {
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Prossimo tentativo.
        }
    }
}

/** Vero se [message] è uno dei due messaggi di rete bloccata: sotto va il pulsante di [NetworkBlockSettingsButton]. */
fun isNetworkBlockMessage(@StringRes message: Int?): Boolean =
    message == R.string.network_blocked_data_saver || message == R.string.network_blocked_suspected

/** Il pulsante che apre le impostazioni di Android per il messaggio di rete bloccata [message]. */
@Composable
fun NetworkBlockSettingsButton(@StringRes message: Int) {
    val context = LocalContext.current
    val dataSaver = message == R.string.network_blocked_data_saver
    TextButton(onClick = { openNetworkSettings(context, dataSaver) }) {
        Text(stringResource(if (dataSaver) R.string.network_blocked_data_saver_settings else R.string.network_blocked_settings))
    }
}
