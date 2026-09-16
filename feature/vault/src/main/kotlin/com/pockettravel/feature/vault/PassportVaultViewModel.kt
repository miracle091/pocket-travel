package com.pockettravel.feature.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.Passport
import com.pockettravel.core.data.PassportRepository
import com.pockettravel.core.data.crypto.VaultKeyEnvelope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.crypto.Cipher
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class PassportVaultViewModel @Inject constructor(
    private val repository: PassportRepository,
    private val keyEnvelope: VaultKeyEnvelope,
) : ViewModel() {

    // Nessuno stato di sblocco persistito qui: la schermata ripresenta sempre il prompt
    // biometrico quando viene aperta (vedi PassportVaultScreen), coerente col gating
    // "a livello di schermata" scelto per la v1. La chiave di sessione ottenuta dallo sblocco
    // vive solo in repository (in memoria) finche' PassportVaultScreen non chiama lock().
    val passports: StateFlow<List<Passport>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun prepareUnlockCipher(): VaultKeyEnvelope.UnlockCipher = keyEnvelope.prepareUnlockCipher()

    fun completeUnlock(intent: VaultKeyEnvelope.UnlockCipher, authenticatedCipher: Cipher) {
        repository.unlock(keyEnvelope.completeUnlock(intent, authenticatedCipher))
    }

    fun lock() {
        repository.lock()
    }

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
}
