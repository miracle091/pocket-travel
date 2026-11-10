package com.pockettravel.feature.vault

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.Passport
import com.pockettravel.core.data.PassportRepository
import com.pockettravel.core.data.crypto.VaultKeyEnvelope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.inject.Inject

/** Avanzamento di [VaultViewModel.resetVault]. */
enum class VaultResetState { IDLE, RESETTING, DONE, FAILED }

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val repository: PassportRepository,
    private val keyEnvelope: VaultKeyEnvelope,
    private val photoLoader: PhotoLoader,
) : ViewModel() {

    // Nessuno stato di sblocco persistito qui: la schermata ripresenta sempre il prompt
    // biometrico quando viene aperta (vedi VaultScreen), coerente col gating
    // "a livello di schermata". La chiave di sessione ottenuta dallo sblocco
    // vive solo in repository (in memoria) finche' VaultScreen non chiama lock().
    //
    // Da bloccata emette la lista vuota (niente dati decifrati in memoria); allo sblocco rilegge dal
    // database con la chiave nuova.
    private val unlocked = MutableStateFlow(repository.isUnlocked)

    @OptIn(ExperimentalCoroutinesApi::class)
    val passports: StateFlow<List<Passport>> = unlocked
        .flatMapLatest { if (it) repository.observeAll() else flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Miniature gia' decodificate: evitano di decifrare e decodificare la foto intera a ogni rientro
    // nella lista. Solo in memoria, svuotata al blocco; misurata in byte dei bitmap (max 4 MiB).
    private val thumbnails = ThumbnailCache(THUMBNAIL_CACHE_BYTES)

    fun cachedThumbnail(fileName: String): ImageBitmap? = thumbnails.get(fileName)

    // Null finché la cassaforte è bloccata o se la foto non si legge. Da chiamare dal main thread, come
    // lock(): così il controllo di sblocco e l'inserimento in cache non si separano.
    suspend fun thumbnail(fileName: String, targetPx: Int): ImageBitmap? =
        thumbnails.get(fileName) ?: photo(fileName, targetPx)?.also { image ->
            // Una decodifica finita dopo il blocco non deve rimettere in cache una foto in chiaro.
            if (repository.isUnlocked) thumbnails.put(fileName, image)
        }

    suspend fun photo(fileName: String, targetPx: Int): ImageBitmap? =
        repository.loadPhoto(fileName)?.let { bytes -> photoLoader.decode(bytes, targetPx) }

    fun prepareUnlockCipher(): VaultKeyEnvelope.UnlockCipher? = keyEnvelope.prepareUnlockCipher()

    private val _resetState = MutableStateFlow(VaultResetState.IDLE)
    val resetState: StateFlow<VaultResetState> = _resetState.asStateFlow()

    // Chiave invalidata (impronte cambiate): i passaporti non si leggono piu', si ricomincia da vuoto.
    // La schermata resta in attesa finche' [resetState] non e' DONE; con FAILED si puo' riprovare.
    fun resetVault() {
        if (_resetState.value == VaultResetState.RESETTING) return
        _resetState.value = VaultResetState.RESETTING
        viewModelScope.launch {
            try {
                repository.deleteAll()
                keyEnvelope.reset()
                thumbnails.evictAll()
                _resetState.value = VaultResetState.DONE
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _resetState.value = VaultResetState.FAILED
            }
        }
    }

    fun completeUnlock(intent: VaultKeyEnvelope.UnlockCipher, authenticatedCipher: Cipher) {
        val dek = keyEnvelope.completeUnlock(intent, authenticatedCipher)
        try {
            // SessionAesCipher ne tiene una copia: la chiave in chiaro non resta in questo array.
            repository.unlock(dek)
        } finally {
            dek.fill(0)
        }
        unlocked.value = true
    }

    fun lock() {
        repository.lock()
        unlocked.value = false
        thumbnails.evictAll()
        drafts.clear()
    }

    // Testi del modulo del documento in modifica, scartati dal blocco.
    internal val drafts = DocumentDrafts()

    // Vero solo dopo una rotazione (la schermata non blocca quando isChangingConfigurations):
    // la chiave di sessione e' ancora in memoria e non serve un nuovo prompt biometrico.
    val isUnlocked: Boolean get() = repository.isUnlocked

    fun save(passport: Passport) {
        viewModelScope.launch { repository.save(passport) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    // Suspend semplice (non viewModelScope.launch): il chiamante (PassportEditDialog) ha bisogno
    // del fileName risultante prima di proseguire, per aggiungerlo alla lista di foto in sospeso.
    // Null se la foto non si salva (per esempio la cassaforte si e' bloccata durante lo scatto).
    suspend fun savePhoto(jpegBytes: ByteArray): String? = try {
        repository.savePhoto(jpegBytes)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    // Chiusura del modulo con le foto [initial] del documento e [current] della bozza. Salvato: si cancellano
    // le foto tolte, solo ora, così un Annulla non rompe i riferimenti del documento già su disco. Non salvato
    // (Annulla o uscita dalla composizione): si cancellano le foto scattate in questa modifica.
    fun discardUnusedPhotos(initial: List<String>, current: List<String>, saved: Boolean) {
        val unused = if (saved) initial - current.toSet() else current - initial.toSet()
        viewModelScope.launch { unused.forEach { repository.deletePhoto(it) } }
    }

    private companion object {
        const val THUMBNAIL_CACHE_BYTES = 4 * 1024 * 1024
    }
}
