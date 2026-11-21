package com.pockettravel.feature.ai

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TranslationModelUiState(
    val isInstalled: Boolean = false,
    // Da 0 a 1 mentre si scarica, null altrimenti.
    val downloadProgress: Float? = null,
    @StringRes val errorMessage: Int? = null,
)

/** La coppia di traduzione italiano-inglese nelle Impostazioni: scarica, annulla, elimina. */
@HiltViewModel
class TranslationModelViewModel @Inject constructor(
    private val manager: BergamotModelManager,
    private val downloads: BergamotModelDownloads,
) : ViewModel() {

    val uiState: StateFlow<TranslationModelUiState> = combine(manager.installed, downloads.observe()) { installed, status ->
        when {
            installed -> TranslationModelUiState(isInstalled = true)
            status is BergamotDownloadStatus.Running -> TranslationModelUiState(downloadProgress = status.fraction)
            status is BergamotDownloadStatus.Failed -> TranslationModelUiState(errorMessage = status.reason.message())
            else -> TranslationModelUiState()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TranslationModelUiState(isInstalled = manager.installed.value))

    fun download() = downloads.enqueue()

    fun cancelDownload() = downloads.cancel()

    fun delete() {
        // Un download in corso riscriverebbe i file appena eliminati.
        downloads.cancel()
        viewModelScope.launch { manager.delete() }
    }

    private fun BergamotDownloadFailure.message(): Int = when (this) {
        BergamotDownloadFailure.INTEGRITY -> R.string.ai_error_integrity
        BergamotDownloadFailure.SPACE -> R.string.translation_model_error_space
        BergamotDownloadFailure.OTHER -> R.string.translation_model_error_download
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
