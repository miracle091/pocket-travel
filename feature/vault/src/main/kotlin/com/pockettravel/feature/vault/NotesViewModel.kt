package com.pockettravel.feature.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.Note
import com.pockettravel.core.data.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// A differenza del vault dei documenti (PassportVaultViewModel), nessuno sblocco biometrico:
// le note sono cifrate a riposo (KeystoreCipher, vedi NoteRepository) ma leggibili subito, cosi'
// l'assistente IA puo' cercarci dentro senza chiedere l'impronta.
@HiltViewModel
class NotesViewModel @Inject constructor(
    private val repository: NoteRepository,
) : ViewModel() {

    val notes: StateFlow<List<Note>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(note: Note) {
        viewModelScope.launch { repository.save(note) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }
}
