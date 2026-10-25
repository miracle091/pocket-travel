package com.pockettravel.app.crash

import com.pockettravel.feature.ai.LoadedModelBreadcrumb
import kotlin.math.abs

/** Dati del dispositivo e dell'app che entrano in ogni rapporto. */
data class DeviceInfo(
    val appVersion: String,
    val androidVersion: String,
    val device: String,
    val totalRamMb: Long,
)

/**
 * Al primo avvio del processo trasforma l'ultima chiusura inattesa del processo precedente in un rapporto in attesa.
 * Il crash JVM lo scrive già il gestore delle eccezioni (stack, schermata): qui non si duplica.
 */
class CrashCollector(
    private val store: CrashStore,
    private val exits: ProcessExitReader,
    private val breadcrumb: LoadedModelBreadcrumb,
    private val device: DeviceInfo,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun collect() {
        // La traccia vale per il processo morto: si legge e si toglie sempre, anche senza chiusure da riportare.
        val loadedModel = breadcrumb.read()
        breadcrumb.clear()
        val recent = exits.recent()
        val lastSeen = store.lastSeen()
        val newest = recent.maxOfOrNull { it.timestamp }
        if (lastSeen == null) {
            // Primo avvio con questa funzione: le chiusure passate non si riportano.
            store.saveLastSeen(maxOf(newest ?: 0L, now()))
        } else {
            if (newest != null && newest > lastSeen) store.saveLastSeen(newest)
            reportable(recent, lastSeen)?.let { (exit, kind) -> store.writePending(report(exit, kind, loadedModel)) }
        }
    }

    private fun reportable(recent: List<ProcessExit>, lastSeen: Long): Pair<ProcessExit, CrashKind>? {
        val exit = selectReportableExit(recent, lastSeen)
        val kind = exit?.reportKind()
        return if (exit != null && kind != null && !isAlreadyPending(exit, kind)) exit to kind else null
    }

    private fun report(exit: ProcessExit, kind: CrashKind, loadedModel: LoadedModelBreadcrumb.Entry?): CrashReport {
        // Il tombstone di un crash nativo porta anche pezzi di memoria vicini ai registri (domanda, contesto, note): non entra
        // nel rapporto. Le tracce degli ANR sono solo pile dei thread.
        val trace = exit.takeIf { kind != CrashKind.NATIVE }?.trace()?.let { readableTrace(it, MAX_TRACE_CHARS) }.orEmpty()
        val details = listOfNotNull(exit.description?.takeIf { it.isNotBlank() }, trace.takeIf { it.isNotBlank() })
        return CrashReport(
            kind = kind,
            occurredAt = exit.timestamp,
            appVersion = device.appVersion,
            androidVersion = device.androidVersion,
            device = device.device,
            totalRamMb = device.totalRamMb,
            loadedModelId = loadedModel?.modelId,
            modelRamTier = loadedModel?.modelTier,
            deviceRamTier = loadedModel?.deviceTier,
            details = details.joinToString("\n"),
        )
    }

    // Lo stesso crash JVM: il gestore ha già scritto il rapporto, con lo stack.
    private fun isAlreadyPending(exit: ProcessExit, kind: CrashKind): Boolean =
        kind == CrashKind.JVM &&
            store.readPending()?.let { it.kind == CrashKind.JVM && abs(it.occurredAt - exit.timestamp) < SAME_EVENT_MS } == true

    private companion object {
        const val MAX_TRACE_CHARS = 3000
        const val SAME_EVENT_MS = 60_000L
    }
}
