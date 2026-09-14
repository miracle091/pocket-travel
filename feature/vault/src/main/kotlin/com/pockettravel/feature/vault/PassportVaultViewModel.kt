package com.pockettravel.feature.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.Passport
import com.pockettravel.core.data.PassportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class PassportVaultViewModel @Inject constructor(
    private val repository: PassportRepository,
) : ViewModel() {

    // Nessuno stato di sblocco persistito qui: la schermata ripresenta sempre il prompt
    // biometrico quando viene aperta (vedi PassportVaultScreen), coerente col gating
    // "a livello di schermata" scelto per la v1.
    val passports: StateFlow<List<Passport>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(passport: Passport) {
        viewModelScope.launch { repository.save(passport) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }
}
