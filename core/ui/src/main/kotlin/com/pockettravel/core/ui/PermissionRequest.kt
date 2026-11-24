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
    val granted by rememberUpdatedState(onGranted)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            granted()
        } else {
            // Dopo un rifiuto, rationale false vuol dire "Non chiedere piu'": il sistema non mostra piu' la richiesta
            // e l'unica strada sono le impostazioni dell'app.
            val activity = context.findActivity()
            val permanentlyDenied = activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            scope.launch {
                val result = snackbarHostState.showSnackbar(message = deniedMessage, actionLabel = if (permanentlyDenied) openSettingsLabel else null)
                if (result == SnackbarResult.ActionPerformed) {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                }
            }
        }
    }
    return { launcher.launch(permission) }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
