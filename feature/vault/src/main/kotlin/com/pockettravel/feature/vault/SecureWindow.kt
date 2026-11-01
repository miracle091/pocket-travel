package com.pockettravel.feature.vault

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * FLAG_SECURE sulla finestra dell'activity finché la funzione è in composizione: niente screenshot e niente contenuto
 * nella miniatura delle app recenti. Si toglie solo se non c'era già prima. I dialoghi hanno una finestra propria e lo
 * chiedono con `securePolicy`.
 */
@Composable
internal fun SecureWindow() {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val window = activity?.window
        val alreadySecure = window != null && (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!alreadySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
