package com.pockettravel.core.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import kotlinx.coroutines.launch

/**
 * Richiesta di [permission]: concesso chiama [onGranted]; negato mostra [deniedMessage] in [snackbarHostState], con
 * [openSettingsLabel] che apre le impostazioni dell'app quando il sistema non mostra piu' la richiesta. Il controllo
 * del permesso gia' concesso resta al chiamante.
 */
@Composable
fun rememberPermissionRequest(
    permission: String,
    snackbarHostState: SnackbarHostState,
    deniedMessage: String,
    openSettingsLabel: String,
    onGranted: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return rememberPermissionRequest(permission, onGranted) { canAskAgain ->
        scope.launch {
            val result = snackbarHostState.showSnackbar(message = deniedMessage, actionLabel = if (canAskAgain) null else openSettingsLabel)
            if (result == SnackbarResult.ActionPerformed) context.openAppSettings()
        }
    }
}

/**
 * Richiesta di [permission] per chi mostra il rifiuto a modo suo: [onDenied] riceve canAskAgain falso quando il sistema
 * non mostra piu' la richiesta e l'unica strada sono le impostazioni dell'app ([openAppSettings]).
 */
@Composable
fun rememberPermissionRequest(permission: String, onGranted: () -> Unit, onDenied: (canAskAgain: Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val granted by rememberUpdatedState(onGranted)
    val denied by rememberUpdatedState(onDenied)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            granted()
        } else {
            // Dopo un rifiuto, rationale false vuol dire "Non chiedere piu'".
            val activity = context.findActivity()
            denied(activity == null || ActivityCompat.shouldShowRequestPermissionRationale(activity, permission))
        }
    }
    return { launcher.launch(permission) }
}

/** Apre la pagina dell'app nelle impostazioni di Android, dove si attivano i permessi negati. */
fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
