package com.pockettravel.feature.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettravel.core.ui.Spacing
import com.pockettravel.feature.ai.llamacpp.CpuBackendInfo

private const val BITS_PER_BYTE = 8

/**
 * Sezione del task manager con la variante CPU di llama.cpp: quella caricata, le estensioni della CPU che la
 * decidono e la scelta per il prossimo avvio, per confrontare le varianti sullo stesso dispositivo (vedi
 * [com.pockettravel.feature.ai.llamacpp.CpuBackendOverride]).
 */
@Composable
internal fun CpuBackendSection(info: CpuBackendInfo?, viewModel: LlmTaskManagerViewModel) {
    val chosen by viewModel.chosenCpuBackend.collectAsStateWithLifecycle()
    val available by viewModel.availableCpuBackends.collectAsStateWithLifecycle()
    SectionTitle(R.string.ai_taskmgr_cpu_backend)
    MetricRow(stringResource(R.string.ai_taskmgr_cpu_extensions), info?.let { cpuExtensions(it) } ?: NOT_AVAILABLE)
    MetricRow(
        stringResource(R.string.ai_taskmgr_cpu_loaded),
        info?.let { loadedVariant(it) } ?: stringResource(R.string.ai_taskmgr_cpu_not_initialized),
    )
    Text(
        text = stringResource(R.string.ai_taskmgr_cpu_next_start),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = Spacing.s),
    )
    Column(modifier = Modifier.selectableGroup()) {
        VariantOption(stringResource(R.string.ai_taskmgr_cpu_auto), selected = chosen == null) { viewModel.chooseCpuBackend(null) }
        available.forEach { file ->
            VariantOption(cpuVariantName(file), selected = chosen == file) { viewModel.chooseCpuBackend(file) }
        }
    }
    // La scelta salvata non e' quella con cui e' partito il processo: serve un riavvio.
    if (info != null && chosen != info.forcedFile) {
        Text(
            text = stringResource(R.string.ai_taskmgr_cpu_restart_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = viewModel::restartApp) { Text(stringResource(R.string.ai_taskmgr_cpu_restart)) }
    }
}

@Composable
private fun cpuExtensions(info: CpuBackendInfo): String {
    val parts = buildList {
        if (info.sve2) add("SVE2")
        if (info.sme) add("SME")
        if (info.sveVectorBytes > 0) add(stringResource(R.string.ai_taskmgr_cpu_sve_bits, info.sveVectorBytes * BITS_PER_BYTE))
    }
    return if (parts.isEmpty()) stringResource(R.string.ai_taskmgr_cpu_extensions_none) else parts.joinToString(" · ")
}

@Composable
private fun loadedVariant(info: CpuBackendInfo): String {
    val loaded = info.loadedFile?.let(::cpuVariantName) ?: NOT_AVAILABLE
    val forced = info.forcedFile ?: return stringResource(R.string.ai_taskmgr_cpu_loaded_auto, loaded)
    return if (forced == info.loadedFile) {
        stringResource(R.string.ai_taskmgr_cpu_loaded_forced, loaded)
    } else {
        stringResource(R.string.ai_taskmgr_cpu_forced_refused, loaded, cpuVariantName(forced))
    }
}

@Composable
private fun VariantOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // onClick null: il clic lo gestisce la riga intera, cosi' TalkBack legge un solo elemento.
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = Spacing.s))
    }
}
