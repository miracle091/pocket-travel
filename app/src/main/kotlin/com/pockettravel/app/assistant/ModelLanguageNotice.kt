package com.pockettravel.app.assistant

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.core.content.edit
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pockettravel.app.R
import com.pockettravel.core.data.PreferencesFactory
import com.pockettravel.core.ui.Spacing
import com.pockettravel.feature.ai.AiSettingsStore
import com.pockettravel.feature.ai.DeviceAiCapability
import com.pockettravel.feature.ai.LlmModelCatalog
import com.pockettravel.feature.ai.LlmModelDefinition
import com.pockettravel.feature.ai.LlmModelDownloadScheduler
import com.pockettravel.feature.ai.LlmModelManager
import com.pockettravel.feature.ai.ModelOrigin
import com.pockettravel.feature.ai.RamTier
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn

/** Modello addestrato scaricato nell'altra lingua e quello da proporre per la lingua dell'app. */
data class ModelLanguageProposal(val downloaded: LlmModelDefinition, val proposed: LlmModelDefinition)

/**
 * Null se tra i modelli scaricati ce n'e' gia' uno che risponde in [language] (anche un modello ufficiale), o se nessun
 * modello addestrato scaricato e' in un'altra lingua: altrimenti il modello addestrato in [language] da proporre.
 */
internal fun modelForLanguage(downloaded: Set<String>, tier: RamTier, language: String): ModelLanguageProposal? {
    if (LlmModelCatalog.visibleFor(tier, language).any { it.id in downloaded }) return null
    val other = LlmModelCatalog.ALL.firstOrNull { it.origin == ModelOrigin.ADDESTRATO && it.language != language && it.id in downloaded }
    // La stessa taglia di quello scaricato, se il dispositivo la mostra; altrimenti il predefinito per la RAM.
    val proposed = LlmModelCatalog.ALL.firstOrNull {
        it.origin == ModelOrigin.ADDESTRATO && it.language == language && it.sha256 != null && it.minRamTier == other?.minRamTier && LlmModelCatalog.isVisibleIn(it, tier)
    } ?: LlmModelCatalog.defaultFor(tier, language).takeIf { it.origin == ModelOrigin.ADDESTRATO && it.language == language }
    return if (other != null && proposed != null) ModelLanguageProposal(other, proposed) else null
}

/**
 * Dopo un cambio di lingua: propone il modello addestrato nella lingua nuova, senza scaricarlo da solo (pesa centinaia
 * di MB). Una risposta, "Scarica" o "Non ora", vale per quella lingua: l'avviso non torna finche' non cambia di nuovo.
 */
@HiltViewModel
class ModelLanguageNoticeViewModel @Inject constructor(
    modelManager: LlmModelManager,
    private val aiSettingsStore: AiSettingsStore,
    private val downloadScheduler: LlmModelDownloadScheduler,
    capability: DeviceAiCapability,
    preferencesFactory: PreferencesFactory,
) : ViewModel() {
    private val preferences = preferencesFactory.open(PREFS)
    private val language = MutableStateFlow<String?>(null)
    private val answered = MutableStateFlow(preferences.getString(KEY_ANSWERED_LANGUAGE, null))

    val proposal: StateFlow<ModelLanguageProposal?> =
        combine(language.filterNotNull(), modelManager.downloadedModelIds, answered) { current, downloaded, answeredLanguage ->
            if (current == answeredLanguage) null else modelForLanguage(downloaded, capability.ramTier(), current)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun onLanguage(current: String) {
        language.value = current
    }

    fun dismiss() = answer()

    /** Stesso flusso del download dalla lista dei modelli: sceglie il modello e avvia il download. */
    fun download(proposal: ModelLanguageProposal) {
        aiSettingsStore.setSelectedModelId(proposal.proposed.id)
        downloadScheduler.enqueueDownload(proposal.proposed.id)
        answer()
    }

    private fun answer() {
        val current = language.value ?: return
        preferences.edit { putString(KEY_ANSWERED_LANGUAGE, current) }
        answered.value = current
    }

    private companion object {
        const val PREFS = "model_language_notice"
        const val KEY_ANSWERED_LANGUAGE = "answered_language"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Foglio con la proposta; non compare se non c'e' niente da proporre. */
@Composable
fun ModelLanguageNoticeHost(viewModel: ModelLanguageNoticeViewModel = hiltViewModel()) {
    // Dalla configurazione: cambia con la lingua dell'app anche senza ricreare l'activity.
    val language = if (LocalConfiguration.current.locales[0].language == "en") "en" else "it"
    LaunchedEffect(language) { viewModel.onLanguage(language) }
    val proposal by viewModel.proposal.collectAsStateWithLifecycle()
    proposal?.let { ModelLanguageSheet(it, language, onDismiss = viewModel::dismiss, onDownload = { viewModel.download(it) }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelLanguageSheet(proposal: ModelLanguageProposal, language: String, onDismiss: () -> Unit, onDownload: () -> Unit) {
    val context = LocalContext.current
    val appLocale = Locale.forLanguageTag(language)
    val downloadedLanguage = Locale.forLanguageTag(proposal.downloaded.language.orEmpty()).getDisplayLanguage(appLocale)
    val size = Formatter.formatShortFileSize(context, proposal.proposed.sizeBytes)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl).padding(bottom = Spacing.xxl)) {
            Text(
                text = stringResource(R.string.model_language_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(
                    R.string.model_language_text,
                    proposal.downloaded.displayName,
                    downloadedLanguage,
                    appLocale.getDisplayLanguage(appLocale),
                    proposal.proposed.displayName,
                    size,
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = Spacing.m),
            )
            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.model_language_download))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.model_language_not_now))
            }
        }
    }
}
