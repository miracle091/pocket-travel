package com.pockettravel.feature.ai

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.ui.Spacing
import kotlinx.coroutines.launch
import java.util.Locale

/** Task manager dei modelli IA, aggiornato ogni secondo. Solo build di debug. */
@Composable
internal fun LlmTaskManagerDialog(onDismiss: () -> Unit, viewModel: LlmTaskManagerViewModel = hiltViewModel()) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_close)) } },
        dismissButton = { ExportLogButton(viewModel) },
        title = { Text(stringResource(R.string.ai_taskmgr_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                snapshot?.let { current ->
                    SpaceSection(current)
                    ResourcesSection(current)
                    BehaviourSection(current.runtime)
                    CpuBackendSection(current.cpuBackend, viewModel)
                } ?: Text(stringResource(R.string.ai_taskmgr_loading))
            }
        },
    )
}

@Composable
private fun SpaceSection(snapshot: LlmTaskSnapshot) {
    SectionTitle(R.string.ai_taskmgr_space)
    if (snapshot.models.isEmpty()) {
        MetricRow(stringResource(R.string.ai_taskmgr_no_models), "—")
    }
    snapshot.models.forEach { MetricRow(it.fileName, formatBytes(it.sizeBytes)) }
    MetricRow(stringResource(R.string.ai_taskmgr_models_total), formatBytes(snapshot.models.sumOf { it.sizeBytes }))
    MetricRow(stringResource(R.string.ai_taskmgr_free_storage), formatBytes(snapshot.freeStorageBytes))
}

@Composable
private fun ResourcesSection(snapshot: LlmTaskSnapshot) {
    SectionTitle(R.string.ai_taskmgr_resources)
    MetricRow(stringResource(R.string.ai_taskmgr_pss), formatBytes(snapshot.totalPssBytes))
    MetricRow(
        stringResource(R.string.ai_taskmgr_native_pss),
        snapshot.nativePssBytes?.let { formatBytes(it) } ?: stringResource(R.string.ai_taskmgr_unavailable),
    )
    MetricRow(stringResource(R.string.ai_taskmgr_native_heap), formatBytes(snapshot.nativeHeapBytes))
    MetricRow(
        stringResource(R.string.ai_taskmgr_system_ram),
        stringResource(
            R.string.ai_taskmgr_system_ram_value,
            formatBytes(snapshot.systemAvailableRamBytes),
            formatBytes(snapshot.systemTotalRamBytes),
        ),
    )
    MetricRow(stringResource(R.string.ai_taskmgr_low_memory), stringResource(if (snapshot.lowMemory) R.string.ai_taskmgr_yes else R.string.ai_taskmgr_no))
    MetricRow(
        stringResource(R.string.ai_taskmgr_cpu),
        stringResource(R.string.ai_taskmgr_cpu_value, snapshot.cpuPercent, snapshot.cpuCores),
    )
    MetricRow(stringResource(R.string.ai_taskmgr_threads), snapshot.threadCount?.toString() ?: NOT_AVAILABLE)
}

@Composable
private fun BehaviourSection(runtime: LlmRuntimeStats) {
    SectionTitle(R.string.ai_taskmgr_behaviour)
    val modelName = runtime.loadedModelId?.let { id -> LlmModelCatalog.ALL.firstOrNull { it.id == id }?.displayName ?: id }
    MetricRow(stringResource(R.string.ai_taskmgr_state), stateLabel(runtime, modelName))
    MetricRow(stringResource(R.string.ai_taskmgr_load_time), runtime.loadTimeMs?.let { seconds(it) } ?: NOT_AVAILABLE)
    val last = runtime.lastGeneration
    if (last == null) {
        MetricRow(stringResource(R.string.ai_taskmgr_last_generation), stringResource(R.string.ai_taskmgr_none_yet))
        return
    }
    val context = last.context
    MetricRow(stringResource(R.string.ai_taskmgr_prompt_tokens), context?.promptTokens?.toString() ?: NOT_AVAILABLE)
    MetricRow(stringResource(R.string.ai_taskmgr_generated), context?.generatedTokens?.toString() ?: NOT_AVAILABLE)
    MetricRow(
        stringResource(R.string.ai_taskmgr_context_used),
        context?.let {
            stringResource(R.string.ai_taskmgr_context_used_value, it.usedTokens, it.contextSize, it.usedTokens * PERCENT_INT / it.contextSize)
        } ?: NOT_AVAILABLE,
    )
    MetricRow(stringResource(R.string.ai_taskmgr_first_token), last.timeToFirstTokenMs?.let { seconds(it) } ?: NOT_AVAILABLE)
    MetricRow(stringResource(R.string.ai_taskmgr_total_time), seconds(last.totalMs))
    SpeedRow(R.string.ai_taskmgr_speed, last.tokensPerSecond)
    SpeedRow(R.string.ai_taskmgr_prompt_speed, last.promptTokensPerSecond)
}

@Composable
private fun SpeedRow(@StringRes labelRes: Int, tokensPerSecond: Float?) {
    MetricRow(
        stringResource(labelRes),
        tokensPerSecond?.let { stringResource(R.string.ai_taskmgr_speed_value, it) } ?: NOT_AVAILABLE,
    )
}

@Composable
private fun stateLabel(runtime: LlmRuntimeStats, modelName: String?): String = when {
    runtime.isGenerating -> stringResource(R.string.ai_taskmgr_state_generating, modelName ?: NOT_AVAILABLE)
    modelName != null -> stringResource(R.string.ai_taskmgr_state_loaded, modelName)
    else -> stringResource(R.string.ai_taskmgr_state_unloaded)
}

internal const val NOT_AVAILABLE = "—"
private const val PERCENT_INT = 100

private fun seconds(ms: Long): String = String.format(Locale.getDefault(), "%.2f s", ms / MS_PER_SECOND)

@Composable
internal fun SectionTitle(@StringRes titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Spacing.s, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
internal fun MetricRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// Esporta letture, generazioni e logcat in un file di testo scelto con il selettore di sistema.
@Composable
private fun ExportLogButton(viewModel: LlmTaskManagerViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            scope.launch {
                val message = if (viewModel.export(uri)) R.string.ai_taskmgr_export_done else R.string.ai_taskmgr_export_failed
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    TextButton(onClick = { launcher.launch("pocket-travel-ai-${System.currentTimeMillis()}.txt") }) {
        Text(stringResource(R.string.ai_taskmgr_export))
    }
}
