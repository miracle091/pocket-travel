package com.pockettravel.app.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.pockettravel.core.data.PreferencesFactory
import com.pockettravel.core.sync.UpdateAvailableNotifier
import com.pockettravel.core.sync.installedAppVersionCode
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** La versione che l'app ha consegnato a PackageInstaller: distingue un aggiornamento fatto dall'app da uno fatto dal browser. */
@Singleton
class PendingAppInstall @Inject constructor(preferences: PreferencesFactory) {
    private val prefs = preferences.open(PREFS_NAME)

    fun set(versionCode: Int) = prefs.edit { putInt(KEY_VERSION_CODE, versionCode) }

    /** Vero se la versione installata è quella consegnata dall'app; il segno si toglie sempre, anche dopo un aggiornamento dal browser. */
    fun takeInstalled(installedVersionCode: Long): Boolean {
        val pending = prefs.getInt(KEY_VERSION_CODE, 0)
        prefs.edit { remove(KEY_VERSION_CODE) }
        return pending > 0 && pending.toLong() == installedVersionCode
    }

    private companion object {
        const val PREFS_NAME = "app_install"
        const val KEY_VERSION_CODE = "pending_version_code"
    }
}

/**
 * `ACTION_MY_PACKAGE_REPLACED`: Android chiude l'app per sostituirla e non la riapre. Se l'aggiornamento è partito da "Installa",
 * una notifica la riapre; dopo un aggiornamento dal browser no, perché l'installatore di sistema ha già il suo "Apri".
 */
class AppUpdatedReceiver : BroadcastReceiver() {
    // Un EntryPoint a mano, come AppInstallReceiver: @AndroidEntryPoint su un receiver in Kotlin chiede `super.onReceive`.
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun pendingAppInstall(): PendingAppInstall
        fun updateAvailableNotifier(): UpdateAvailableNotifier
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val dependencies = EntryPointAccessors.fromApplication(context.applicationContext, Dependencies::class.java)
        val versionName = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        if (dependencies.pendingAppInstall().takeInstalled(installedAppVersionCode(context)) && versionName != null) {
            dependencies.updateAvailableNotifier().notifyAppUpdated(versionName)
        }
    }
}
