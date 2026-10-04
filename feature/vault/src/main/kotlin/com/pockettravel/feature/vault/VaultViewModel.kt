package com.pockettravel.feature.vault

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.Passport
import com.pockettravel.core.data.PassportRepository
import com.pockettravel.core.data.crypto.VaultKeyEnvelope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.inject.Inject

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val repository: PassportRepository,
    private val keyEnvelope: VaultKeyEnvelope,
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
    private val thumbnails = object : LruCache<String, ImageBitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * BYTES_PER_PIXEL
    }

    fun cachedThumbnail(fileName: String): ImageBitmap? = thumbnails.get(fileName)

    fun cacheThumbnail(fileName: String, image: ImageBitmap) {
        // Una decodifica finita dopo il blocco non deve rimettere in cache una foto in chiaro.
        if (repository.isUnlocked) thumbnails.put(fileName, image)
    }

    fun prepareUnlockCipher(): VaultKeyEnvelope.UnlockCipher? = keyEnvelope.prepareUnlockCipher()

    // Chiave invalidata (impronte cambiate): i passaporti non si leggono piu', si ricomincia da vuoto.
    fun resetVault() {
        viewModelScope.launch {
            repository.deleteAll()
            keyEnvelope.reset()
            thumbnails.evictAll()
        }
    }

    fun completeUnlock(intent: VaultKeyEnvelope.UnlockCipher, authenticatedCipher: Cipher) {
        repository.unlock(keyEnvelope.completeUnlock(intent, authenticatedCipher))
        unlocked.value = true
    }

    fun lock() {
        repository.lock()
        unlocked.value = false
        thumbnails.evictAll()
    }

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
    suspend fun savePhoto(jpegBytes: ByteArray): String = repository.savePhoto(jpegBytes)

    suspend fun loadPhoto(fileName: String): ByteArray? = repository.loadPhoto(fileName)

    fun discardPhoto(fileName: String) {
        viewModelScope.launch { repository.deletePhoto(fileName) }
    }

    private companion object {
        const val THUMBNAIL_CACHE_BYTES = 4 * 1024 * 1024
        const val BYTES_PER_PIXEL = 4
    }
}
