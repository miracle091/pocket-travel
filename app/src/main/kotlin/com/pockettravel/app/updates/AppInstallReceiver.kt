package com.pockettravel.app.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.ActivityNotFoundException
import androidx.core.content.IntentCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Riceve l'esito della sessione di [PackageAppInstaller]: non esportato, lo scrive solo il sistema. */
class AppInstallReceiver : BroadcastReceiver() {
    // Un EntryPoint a mano: @AndroidEntryPoint su un receiver in Kotlin chiede `super.onReceive`, che è astratto.
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Results {
        fun appInstallResults(): AppInstallResults
    }

    override fun onReceive(context: Context, intent: Intent) {
        val results = EntryPointAccessors.fromApplication(context.applicationContext, Results::class.java).appInstallResults()
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> openSystemDialog(context, intent, results)
            // Android chiude l'app per sostituirla e non la riapre: non c'è altro da fare.
            PackageInstaller.STATUS_SUCCESS -> Unit
            // Include STATUS_FAILURE_ABORTED, l'utente che annulla nella finestra di sistema.
            else -> results.set(AppInstallStatus.Failed)
        }
    }

    private fun openSystemDialog(context: Context, intent: Intent, results: AppInstallResults) {
        val dialog = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        if (dialog == null) {
            results.set(AppInstallStatus.Failed)
            return
        }
        try {
            context.startActivity(dialog.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            results.set(AppInstallStatus.Failed)
        }
    }
}
