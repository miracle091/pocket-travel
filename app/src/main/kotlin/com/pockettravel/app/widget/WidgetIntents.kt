package com.pockettravel.app.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.pockettravel.app.MainActivity
import com.pockettravel.core.sync.UpdateAvailableNotifier

private const val OPEN_ACTION_PREFIX = "com.pockettravel.widget.OPEN."

/**
 * Apre l'app sulla schermata [screen] (valori di `EXTRA_OPEN_SCREEN`, vedi `openScreenTarget`), o solo l'app se null. Passa da
 * MainActivity e dalla navigazione di sempre: Documenti chiede lo sblocco biometrico come dal menu. L'azione cambia con la
 * schermata perche' i PendingIntent con lo stesso intent (extra esclusi) si sovrascriverebbero tra loro.
 */
internal fun openAppIntent(context: Context, screen: String? = null): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = if (screen == null) Intent.ACTION_MAIN else OPEN_ACTION_PREFIX + screen
        if (screen != null) putExtra(UpdateAvailableNotifier.EXTRA_OPEN_SCREEN, screen)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

/** Il tastierino con [number] gia' scritto: ACTION_DIAL non chiede permessi e non chiama da solo. */
internal fun dialIntent(number: String): Intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
