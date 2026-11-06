package com.pockettravel.core.sync

import android.content.Context
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import com.pockettravel.core.data.PreferencesFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Versione minima dell'app per i dati pubblicati ("minAppVersionCode" di `manifest.json`, alzata dalla
 * pipeline quando il formato dei dati cambia in modo incompatibile, es. guide e civici solo in xz).
 * ManifestClient la salva a ogni sync; RegionSyncScheduler non avvia download che l'app non saprebbe
 * leggere e, se li ha chiesti l'utente, lo invita ad aggiornare l'app invece di fallire in silenzio
 * ([updateRequiredMessages], mostrato dall'app con l'azione che apre Aggiornamenti).
 * Il catalogo resta consultabile: i campi nuovi di `manifest.json` un'app vecchia li ignora.
 */
@Singleton
class AppCompatibility internal constructor(
    preferences: PreferencesFactory,
    private val installedVersionCode: () -> Long,
) {
    @Inject constructor(@ApplicationContext context: Context, preferences: PreferencesFactory) : this(
        preferences,
        installedVersionCode = { installedAppVersionCode(context) },
    )

    private val prefs = preferences.open("app_compatibility")

    fun update(minAppVersionCode: Int?) = prefs.edit {
        if (minAppVersionCode == null) remove(KEY_MIN_VERSION_CODE) else putInt(KEY_MIN_VERSION_CODE, minAppVersionCode)
    }

    fun requiresAppUpdate(): Boolean = prefs.getInt(KEY_MIN_VERSION_CODE, 0) > installedVersionCode()

    // Senza chi lo raccoglie (nessuna schermata aperta) il messaggio si perde: lo chiede solo un download toccato dall'utente.
    private val _updateRequiredMessages = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Un evento per ogni download bloccato chiesto dall'utente: l'app mostra il messaggio con l'azione "Aggiornamenti". */
    val updateRequiredMessages: SharedFlow<Unit> = _updateRequiredMessages.asSharedFlow()

    /** Breve messaggio all'utente che ha appena chiesto un download bloccato. */
    fun showUpdateRequiredMessage() {
        _updateRequiredMessages.tryEmit(Unit)
    }

    private companion object {
        const val KEY_MIN_VERSION_CODE = "min_version_code"
    }
}

fun installedAppVersionCode(context: Context): Long =
    PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

