package com.pockettravel.core.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import com.pockettravel.core.data.PreferencesFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Versione minima dell'app per i dati pubblicati ("minAppVersionCode" di `manifest.json`, alzata dalla
 * pipeline quando il formato dei dati cambia in modo incompatibile, es. guide e civici solo in xz).
 * ManifestClient la salva a ogni sync; RegionSyncScheduler non avvia download che l'app non saprebbe
 * leggere e, se li ha chiesti l'utente, lo invita ad aggiornare l'app invece di fallire in silenzio.
 * Il catalogo resta consultabile: i campi nuovi di `manifest.json` un'app vecchia li ignora.
 */
@Singleton
class AppCompatibility internal constructor(
    preferences: PreferencesFactory,
    private val installedVersionCode: () -> Long,
    private val showMessage: () -> Unit,
) {
    @Inject constructor(@ApplicationContext context: Context, preferences: PreferencesFactory) : this(
        preferences,
        installedVersionCode = { installedAppVersionCode(context) },
        showMessage = { showUpdateRequiredToast(context) },
    )

    private val prefs = preferences.open("app_compatibility")

    fun update(minAppVersionCode: Int?) = prefs.edit {
        if (minAppVersionCode == null) remove(KEY_MIN_VERSION_CODE) else putInt(KEY_MIN_VERSION_CODE, minAppVersionCode)
    }

    fun requiresAppUpdate(): Boolean = prefs.getInt(KEY_MIN_VERSION_CODE, 0) > installedVersionCode()

    /** Breve messaggio all'utente che ha appena chiesto un download bloccato. */
    fun showUpdateRequiredMessage() = showMessage()

    private companion object {
        const val KEY_MIN_VERSION_CODE = "min_version_code"
    }
}

fun installedAppVersionCode(context: Context): Long =
    PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

private fun showUpdateRequiredToast(context: Context) {
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(context, R.string.app_update_required_for_download, Toast.LENGTH_LONG).show()
    }
}
