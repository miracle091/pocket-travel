package com.pockettravel.feature.ai

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// La conversazione aperta: turni, token, soglia di compattazione, durata, archiviazioni e riprese, stato. I modelli a turno
// singolo hanno nel contesto solo l'ultimo turno e non compattano; quelli con la cronologia vera (historyInContext) anche i
// turni di prima, che il motore accorcia se non entrano.
@Composable
internal fun ConversationSection(info: ConversationDebugInfo?, runtime: LlmRuntimeStats) {
    SectionTitle(R.string.ai_taskmgr_conversation)
    if (info == null) {
        MetricRow(stringResource(R.string.ai_taskmgr_conversation_none), NOT_AVAILABLE)
        return
    }
    val now = System.currentTimeMillis()
    MetricRow(
        stringResource(R.string.ai_taskmgr_conversation_saving),
        stringResource(if (info.saved) R.string.ai_taskmgr_conversation_saved else R.string.ai_taskmgr_conversation_not_saved),
    )
    val last = runtime.lastGeneration?.context
    MetricRow(stringResource(R.string.ai_taskmgr_conversation_turns), "${info.turns} (${minOf(info.turns, 1) + (last?.historyTurns ?: 0)})")
    MetricRow(stringResource(R.string.ai_taskmgr_conversation_history_tokens), (last?.historyTokens ?: 0).toString())
    // Da questa apertura: le generazioni di prima della ripresa non sono piu' in memoria.
    MetricRow(stringResource(R.string.ai_taskmgr_conversation_tokens), conversationTokens(runtime.history, info.lastResumedAtMs ?: info.startedAtMs).toString())
    MetricRow(
        stringResource(R.string.ai_taskmgr_conversation_threshold),
        last?.let {
            val threshold = compactionThreshold(it.contextSize)
            val remaining = maxOf(0, threshold - it.usedTokens)
            pluralStringResource(R.plurals.ai_taskmgr_conversation_threshold_value, remaining, threshold, remaining)
        } ?: NOT_AVAILABLE,
    )
    MetricRow(stringResource(R.string.ai_taskmgr_conversation_compactions), runtime.compactions.toString())
    MetricRow(stringResource(R.string.ai_taskmgr_conversation_duration), elapsed(now - info.startedAtMs))
    MetricRow(stringResource(R.string.ai_taskmgr_conversation_since_last), elapsed(now - info.lastTurnAtMs))
    MetricRow(stringResource(R.string.ai_taskmgr_conversation_archives), info.archiveCount.toString())
    MetricRow(
        stringResource(R.string.ai_taskmgr_conversation_resumes),
        info.lastResumedAtMs?.let { "${info.resumeCount} (${clockTime(it)})" } ?: info.resumeCount.toString(),
    )
    MetricRow(stringResource(R.string.ai_taskmgr_conversation_region), info.state.regionId)
    MetricRow(stringResource(R.string.ai_taskmgr_conversation_cities), info.state.cities.joinToString().ifEmpty { NOT_AVAILABLE })
    MetricRow(stringResource(R.string.ai_taskmgr_conversation_destination), info.state.destination ?: NOT_AVAILABLE)
    MetricRow(stringResource(R.string.ai_taskmgr_conversation_last_question), info.state.lastQuestion ?: NOT_AVAILABLE)
}

// Durata come h:mm:ss.
private fun elapsed(ms: Long): String {
    val total = maxOf(0L, ms) / MS_PER_SECOND.toLong()
    return String.format(Locale.getDefault(), "%d:%02d:%02d", total / SECONDS_PER_HOUR, total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE, total % SECONDS_PER_MINUTE)
}

private fun clockTime(epochMs: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600
