package com.pockettravel.app.crash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import com.pockettravel.feature.ai.LlmModelCatalog
import com.pockettravel.feature.ai.LoadedModelBreadcrumb
import com.pockettravel.feature.ai.RamTier

/**
 * Solo debug: provoca le chiusure inattese per provare il foglio del rapporto.
 *
 * `adb shell am broadcast -n com.pockettravel.app/.crash.DebugCrashReceiver --es kind jvm|native|lowmem`
 * - `jvm`: eccezione non catturata sul thread principale.
 * - `native`: SIGABRT al processo (tombstone, come un `abort()` di llama.cpp).
 * - `lowmem`: scrive il rapporto di una chiusura per memoria con il modello più grande del catalogo su un
 *   dispositivo di fascia MINIMO, per vedere la proposta del modello adatto; il processo non si chiude.
 */
class DebugCrashReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra(EXTRA_KIND)) {
            "jvm" -> {
                // Una traccia del modello come dopo il caricamento di un modello grande.
                writeLargeModelBreadcrumb(context)
                android.os.Handler(context.mainLooper).post { error("Crash di prova") }
            }
            "native" -> {
                writeLargeModelBreadcrumb(context)
                Process.sendSignal(Process.myPid(), SIGABRT)
            }
            "lowmem" -> writeLowMemoryReport(context)
        }
    }

    private fun writeLargeModelBreadcrumb(context: Context) {
        val model = LlmModelCatalog.ALL.maxBy { it.minRamTier }
        LoadedModelBreadcrumb.forContext(context).write(LoadedModelBreadcrumb.Entry(model.id, model.minRamTier, RamTier.MINIMO))
    }

    private fun writeLowMemoryReport(context: Context) {
        val model = LlmModelCatalog.ALL.maxBy { it.minRamTier }
        val reporter = CrashReporter(context.applicationContext)
        reporter.store.writePending(
            CrashReport(
                kind = CrashKind.LOW_MEMORY,
                occurredAt = System.currentTimeMillis(),
                appVersion = "debug",
                androidVersion = android.os.Build.VERSION.RELEASE,
                device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                totalRamMb = 0,
                loadedModelId = model.id,
                modelRamTier = model.minRamTier,
                deviceRamTier = RamTier.MINIMO,
                details = "Rapporto di prova",
            ),
        )
    }

    private companion object {
        const val EXTRA_KIND = "kind"
        const val SIGABRT = 6
    }
}
