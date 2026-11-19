package com.pockettravel.feature.ai

import androidx.work.WorkInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** Lato lungo della foto per il modello sul dispositivo: cartelli e menu chiedono almeno 768 px, e ogni pixel in piu' costa tempo di codifica sulla CPU. */
internal const val LOCAL_PHOTO_LONG_SIDE = 768

/**
 * Il modello [model] legge le foto su un telefono della fascia [tier]: ha un mmproj e il telefono ha la RAM della fascia del
 * modello (l'mmproj pesa circa 600 MB in memoria, oltre al modello: non sui 4 GB).
 */
internal fun canReadPhotos(model: LlmModelDefinition, tier: RamTier): Boolean = model.mmproj != null && tier >= model.minRamTier

/** La foto con l'mmproj per il motore sul dispositivo; null senza foto o senza mmproj. */
internal fun photoInput(photo: ByteArray?, mmproj: File?): OnDeviceLlmEngine.ImageInput? =
    if (photo != null && mmproj != null) OnDeviceLlmEngine.ImageInput(photo, mmproj.absolutePath) else null

/** Il file per le foto eliminato da Spazio di archiviazione con la schermata aperta: lo stato lo crede ancora su disco. */
internal fun AiUiState.lostMmproj(onDisk: Boolean): Boolean = mode == AiEngineMode.ON_DEVICE && isMmprojDownloaded && !onDisk

/** Il modello in uso accetta foto: online quello del servizio, sul dispositivo quello scelto con la RAM giusta. */
internal val AiUiState.photoSupported: Boolean
    get() = when (mode) {
        AiEngineMode.ONLINE -> canAttachPhoto
        AiEngineMode.ON_DEVICE -> canAttachLocalPhoto
    }

/** Sul dispositivo la foto parte solo con l'mmproj gia' scaricato: prima si offre il download. */
internal val AiUiState.needsMmproj: Boolean get() = mode == AiEngineMode.ON_DEVICE && photoSupported && !isMmprojDownloaded

/** La foto resta sul telefono: sul dispositivo non serve nessun consenso. */
internal fun AiUiState.photoConsented(onlineConsent: Boolean): Boolean = mode == AiEngineMode.ON_DEVICE || onlineConsent

/** Lo stato dopo un avanzamento del download dell'mmproj; [isDownloaded] dice se il file e' su disco a lavoro riuscito. */
internal fun AiUiState.afterMmprojWork(
    state: WorkInfo.State,
    fraction: Float,
    reportsFailure: Boolean,
    failureReason: String?,
    isDownloaded: Boolean,
): AiUiState = when (state) {
    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> copy(mmprojProgress = fraction)
    WorkInfo.State.SUCCEEDED -> copy(mmprojProgress = null, offersMmproj = false, isMmprojDownloaded = isDownloaded)
    WorkInfo.State.FAILED ->
        if (reportsFailure) copy(mmprojProgress = null, offersMmproj = false, errorMessage = downloadFailureMessage(failureReason)) else this
    WorkInfo.State.CANCELLED -> copy(mmprojProgress = null)
    WorkInfo.State.BLOCKED -> this
}

/**
 * Le foto sul dispositivo nella schermata dell'assistente: cosa il modello scelto sa fare ([refresh]) e l'offerta e il
 * download dell'mmproj, che parte solo da "Scarica" nella finestra ([offer], [start], [dismiss]).
 */
internal class MmprojDownload(
    private val state: MutableStateFlow<AiUiState>,
    private val store: AiSettingsStore,
    private val manager: LlmModelManager,
    private val scheduler: LlmModelDownloadScheduler,
    private val capability: DeviceAiCapability,
) {
    /** Rilegge dal modello scelto se legge le foto e se il suo mmproj e' su disco. */
    fun refresh() {
        val definition = store.selectedModelDefinition()
        state.update {
            it.copy(canAttachLocalPhoto = canReadPhotos(definition, capability.ramTier()), isMmprojDownloaded = manager.isMmprojDownloaded(definition))
        }
    }

    /** Il tocco su "Allega una foto" senza l'mmproj: apre la finestra che offre il download. */
    fun offer() = state.update { it.copy(offersMmproj = true) }

    /** "Annulla" nella finestra: ferma il download in corso, se c'e', e la chiude. */
    fun dismiss() {
        if (state.value.mmprojProgress != null) scheduler.cancelDownload()
        state.update { it.copy(offersMmproj = false, mmprojProgress = null) }
    }

    /** "Scarica" nella finestra: con poco spazio libero o un download del modello in corso non parte. */
    fun start() {
        val definition = store.selectedModelDefinition()
        val sizeBytes = definition.mmproj?.sizeBytes
        when {
            sizeBytes == null || state.value.downloadProgress != null -> Unit
            manager.availableStorageBytes() < sizeBytes -> state.update { it.copy(offersMmproj = false, errorMessage = R.string.ai_error_space) }
            else -> {
                state.update { it.copy(mmprojProgress = 0f, errorMessage = null) }
                scheduler.enqueueMmprojDownload(definition.id)
            }
        }
    }

    // Un fallimento visto senza averne visto l'avvio e' di un download vecchio: non si mostra (DownloadFailureGate).
    private val failureGate = DownloadFailureGate()

    /** Un avanzamento del lavoro di download dell'mmproj. */
    fun onWork(workInfo: WorkInfo) {
        val reportsFailure = failureGate.reportsFailure(workInfo.state)
        val reason = workInfo.outputData.getString(LlmModelDownloadWorker.KEY_FAILURE_REASON)
        val isDownloaded = manager.isMmprojDownloaded(store.selectedModelDefinition())
        state.update { it.afterMmprojWork(workInfo.state, workInfo.downloadFraction(), reportsFailure, reason, isDownloaded) }
    }
}
