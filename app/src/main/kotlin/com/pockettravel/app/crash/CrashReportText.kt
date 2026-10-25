package com.pockettravel.app.crash

import com.pockettravel.feature.ai.LlmModelCatalog
import com.pockettravel.feature.ai.LlmModelDefinition
import com.pockettravel.feature.ai.RamTier
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val ISSUE_FORM_URL = "https://github.com/miracle091/pocket-travel/issues/new"
private const val ISSUE_TEMPLATE = "problema-app.yml"

// Campi del modulo .github/ISSUE_TEMPLATE/problema-app.yml.
private const val FIELD_APP_VERSION = "versione-app"
private const val FIELD_ANDROID = "android"
private const val FIELD_DEVICE = "dispositivo"
private const val FIELD_REPORT = "rapporto"

/** Lunghezza massima dell'URL del modulo: oltre circa 8 KB GitHub rifiuta la richiesta. */
internal const val MAX_ISSUE_URL_LENGTH = 7000

private const val MAX_STACK_FRAMES = 40
private const val MAX_CAUSES = 5
private const val MIN_PRINTABLE_RUN = 4
private const val PRINTABLE_FIRST = 0x20
private const val PRINTABLE_LAST = 0x7e
private const val TRUNCATED_MARK = "\n[...]"
private const val SHRINK_PERCENT = 90

/** Testo del rapporto, quello che l'utente vede e condivide. */
fun CrashReport.toText(): String = buildString {
    appendLine("Pocket Travel $appVersion")
    appendLine("Android $androidVersion, $device, RAM $totalRamMb MB")
    appendLine("Evento: ${kind.name}, ${formatUtc(occurredAt)}")
    if (screen != null) appendLine("Schermata: $screen")
    if (loadedModelId != null) {
        appendLine("Modello caricato: $loadedModelId (fascia ${modelRamTier?.name ?: "?"}, dispositivo ${deviceRamTier?.name ?: "?"})")
    }
    if (details.isNotBlank()) {
        appendLine()
        append(details.trimEnd())
    }
}.trimEnd()

/**
 * URL del modulo "Problema nell'app" con i campi già compilati. Il campo del rapporto si accorcia finché l'URL
 * codificato sta sotto [maxLength].
 */
fun CrashReport.issueUrl(maxLength: Int = MAX_ISSUE_URL_LENGTH): String {
    val text = toText()
    var keep = text.length
    var url = buildIssueUrl(this, text)
    while (url.length > maxLength && keep > 0) {
        keep = minOf(keep - 1, keep * SHRINK_PERCENT / PERCENT)
        url = buildIssueUrl(this, text.take(keep) + TRUNCATED_MARK)
    }
    return url
}

private const val PERCENT = 100

private fun buildIssueUrl(report: CrashReport, reportText: String): String {
    val fields = listOf(
        "template" to ISSUE_TEMPLATE,
        FIELD_APP_VERSION to report.appVersion,
        FIELD_ANDROID to report.androidVersion,
        FIELD_DEVICE to report.device,
        FIELD_REPORT to reportText,
    )
    return fields.joinToString(separator = "&", prefix = "$ISSUE_FORM_URL?") { (name, value) -> "$name=${encode(value)}" }
}

// URLEncoder scrive lo spazio come "+": nella query di GitHub meglio "%20".
private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

private fun formatUtc(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

/**
 * Classi e righe della traccia di [error] e delle sue cause, senza i messaggi: possono contenere testo dell'utente
 * (un titolo, il testo di una nota).
 */
fun stackDetails(error: Throwable): String = buildString {
    var current: Throwable? = error
    var causes = 0
    while (current != null && causes <= MAX_CAUSES) {
        appendLine(if (causes == 0) current.javaClass.name else "Causato da: ${current.javaClass.name}")
        current.stackTrace.take(MAX_STACK_FRAMES).forEach { appendLine("    at $it") }
        current = current.cause?.takeIf { it !== current }
        causes++
    }
}.trimEnd()

/**
 * Dal contenuto di una traccia di sistema (tombstone, traccia di un ANR) tiene i primi [maxChars] caratteri leggibili:
 * il tombstone è in parte binario, si conservano solo le sequenze di almeno quattro caratteri stampabili.
 */
fun readableTrace(bytes: ByteArray, maxChars: Int): String {
    val text = StringBuilder()
    val run = StringBuilder()
    fun flush() {
        if (run.length >= MIN_PRINTABLE_RUN) text.appendLine(run)
        run.clear()
    }
    for (byte in bytes) {
        val code = byte.toInt()
        if (code in PRINTABLE_FIRST..PRINTABLE_LAST) run.append(code.toChar()) else flush()
        if (text.length >= maxChars) break
    }
    flush()
    return text.take(maxChars).toString().trimEnd()
}

/**
 * Modello caricato e modello da proporre dopo una chiusura per memoria o un crash nativo con un modello di una fascia
 * di RAM sopra quella del dispositivo: il proposto è il predefinito della fascia del dispositivo. Null se il modello
 * non era troppo grande, se le fasce non sono note o se il dispositivo non regge nessun modello.
 */
fun proposedModel(report: CrashReport, language: String): Pair<LlmModelDefinition, LlmModelDefinition>? {
    val loaded = LlmModelCatalog.ALL.firstOrNull { it.id == report.loadedModelId }
    val modelTier = report.modelRamTier
    val deviceTier = report.deviceRamTier
    val tooBig = (report.kind == CrashKind.LOW_MEMORY || report.kind == CrashKind.NATIVE) &&
        modelTier != null && deviceTier != null && deviceTier != RamTier.INSUFFICIENTE && modelTier > deviceTier
    if (loaded == null || deviceTier == null || !tooBig) return null
    val proposed = LlmModelCatalog.defaultFor(deviceTier, language)
    return if (proposed.id != loaded.id && proposed.minRamTier <= deviceTier) loaded to proposed else null
}
