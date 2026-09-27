package com.pockettravel.core.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Versione minima dell'app per i dati pubblicati ("minAppVersionCode" del manifest, alzata dalla
 * pipeline quando il formato dei dati cambia in modo incompatibile, es. guide e civici solo in xz).
 * ManifestClient la salva a ogni sync; RegionSyncScheduler non avvia download che l'app non saprebbe
 * leggere e, se li ha chiesti l'utente, lo invita ad aggiornare l'app invece di fallire in silenzio.
 * Il catalogo resta consultabile: i campi nuovi del manifest un'app vecchia li ignora.
 */
@Singleton
class AppCompatibility @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs = context.getSharedPreferences("app_compatibility", Context.MODE_PRIVATE)

    fun update(minAppVersionCode: Int?) = prefs.edit {
        if (minAppVersionCode == null) remove(KEY_MIN_VERSION_CODE) else putInt(KEY_MIN_VERSION_CODE, minAppVersionCode)
    }

    fun requiresAppUpdate(): Boolean = prefs.getInt(KEY_MIN_VERSION_CODE, 0) > installedAppVersionCode(context)

    /** Breve messaggio all'utente che ha appena chiesto un download bloccato. */
    fun showUpdateRequiredMessage() {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, R.string.app_update_required_for_download, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val KEY_MIN_VERSION_CODE = "min_version_code"
    }
}

internal fun installedAppVersionCode(context: Context): Long =
    PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
