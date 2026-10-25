package com.pockettravel.app.crash

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.pockettravel.feature.ai.LoadedModelBreadcrumb
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Schermata aperta, per il rapporto: solo il nome della rotta, senza argomenti. */
object CrashContext {
    @Volatile
    var screen: String? = null
}

/**
 * Raccolta dei rapporti sulle chiusure inattese: il gestore delle eccezioni non catturate scrive il rapporto del
 * crash JVM, l'avvio successivo legge le uscite del sistema (crash nativi, ANR, memoria). Il rapporto resta sul
 * dispositivo finché l'utente non lo condivide o lo chiude.
 */
@Singleton
class CrashReporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir = File(context.filesDir, CrashStore.DIR_NAME)
    val store = CrashStore(dir)
    private val breadcrumb = LoadedModelBreadcrumb.forContext(context)
    private val collected = AtomicBoolean(false)

    /** Installa il gestore delle eccezioni non catturate, che richiama quello precedente. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            writeJvmReport(error)
            previous?.uncaughtException(thread, error)
        }
    }

    // Il processo sta morendo: qualunque errore qui non deve coprire quello originale.
    @Suppress("TooGenericExceptionCaught")
    private fun writeJvmReport(error: Throwable) {
        try {
            val loaded = breadcrumb.read()
            val info = deviceInfo()
            store.writePending(
                CrashReport(
                    kind = CrashKind.JVM,
                    occurredAt = System.currentTimeMillis(),
                    appVersion = info.appVersion,
                    androidVersion = info.androidVersion,
                    device = info.device,
                    totalRamMb = info.totalRamMb,
                    loadedModelId = loaded?.modelId,
                    modelRamTier = loaded?.modelTier,
                    deviceRamTier = loaded?.deviceTier,
                    screen = CrashContext.screen,
                    details = stackDetails(error),
                ),
            )
        } catch (_: Throwable) {
            // Nessun rapporto: il sistema chiude comunque il processo.
        }
    }

    /** Legge le uscite del processo precedente, una sola volta per processo. Da chiamare fuori dal thread principale. */
    fun collectOnce() {
        if (!collected.compareAndSet(false, true)) return
        CrashCollector(store, AndroidProcessExitReader(context), breadcrumb, deviceInfo()).collect()
    }

    private fun deviceInfo(): DeviceInfo {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
        return DeviceInfo(
            appVersion = "${packageInfo.versionName} (${PackageInfoCompat.getLongVersionCode(packageInfo)})",
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            totalRamMb = memory.totalMem / BYTES_PER_MB,
        )
    }

    private companion object {
        const val BYTES_PER_MB = 1024L * 1024
    }
}
