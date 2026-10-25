package com.pockettravel.app.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.IOException
import java.io.InputStream

/** Motivo di una chiusura del processo, ridotto a quelli che interessano il rapporto. */
enum class ExitReason { CRASH, CRASH_NATIVE, ANR, LOW_MEMORY, EXIT_SELF, OTHER }

/** Una chiusura passata del processo, come la riporta il sistema (da Android 11). */
class ProcessExit(
    val timestamp: Long,
    val reason: ExitReason,
    val foreground: Boolean,
    val description: String?,
    /** Traccia di sistema (tombstone, traccia di un ANR), letta solo se serve. */
    val trace: () -> ByteArray?,
)

/** Le ultime chiusure del processo, dalla più recente. */
fun interface ProcessExitReader {
    fun recent(): List<ProcessExit>
}

/**
 * Tipo di rapporto per una chiusura, null se non interessa: crash, ANR, memoria e uscita volontaria in primo piano.
 */
fun ProcessExit.reportKind(): CrashKind? = when (reason) {
    ExitReason.CRASH -> CrashKind.JVM
    ExitReason.CRASH_NATIVE -> CrashKind.NATIVE
    ExitReason.ANR -> CrashKind.ANR
    ExitReason.LOW_MEMORY -> CrashKind.LOW_MEMORY
    ExitReason.EXIT_SELF -> if (foreground) CrashKind.EXIT_SELF else null
    ExitReason.OTHER -> null
}

/** La chiusura più recente da riportare tra quelle successive a [lastSeen], null se non ce ne sono. */
fun selectReportableExit(exits: List<ProcessExit>, lastSeen: Long): ProcessExit? =
    exits.filter { it.timestamp > lastSeen && it.reportKind() != null }.maxByOrNull { it.timestamp }

/** Lettura di `ApplicationExitInfo`: sotto Android 11 nessuna chiusura. */
class AndroidProcessExitReader(private val context: Context) : ProcessExitReader {
    override fun recent(): List<ProcessExit> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return readExits()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readExits(): List<ProcessExit> {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS).map { info ->
            ProcessExit(
                timestamp = info.timestamp,
                reason = reasonOf(info.reason),
                foreground = info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
                description = info.description,
                trace = { readTrace(info) },
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readTrace(info: ApplicationExitInfo): ByteArray? = try {
        info.traceInputStream?.use { it.readUpTo(MAX_TRACE_BYTES) }
    } catch (_: IOException) {
        null
    }

    private fun InputStream.readUpTo(maxBytes: Int): ByteArray {
        val buffer = ByteArray(maxBytes)
        var total = 0
        while (total < maxBytes) {
            val read = read(buffer, total, maxBytes - total)
            if (read < 0) break
            total += read
        }
        return buffer.copyOf(total)
    }

    private fun reasonOf(reason: Int): ExitReason = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> ExitReason.CRASH
        ApplicationExitInfo.REASON_CRASH_NATIVE -> ExitReason.CRASH_NATIVE
        ApplicationExitInfo.REASON_ANR -> ExitReason.ANR
        ApplicationExitInfo.REASON_LOW_MEMORY -> ExitReason.LOW_MEMORY
        ApplicationExitInfo.REASON_EXIT_SELF -> ExitReason.EXIT_SELF
        else -> ExitReason.OTHER
    }

    private companion object {
        const val MAX_EXITS = 5
        const val MAX_TRACE_BYTES = 64 * 1024
    }
}
