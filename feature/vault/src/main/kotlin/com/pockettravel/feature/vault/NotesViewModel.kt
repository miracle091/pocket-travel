package com.pockettravel.feature.vault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

// A differenza del vault dei documenti (VaultViewModel), nessuno sblocco biometrico:
// le note sono cifrate a riposo (KeystoreCipher, vedi NoteRepository) ma leggibili subito, cosi'
// l'assistente IA puo' cercarci dentro senza chiedere l'impronta.
@HiltViewModel
class NotesViewModel @Inject constructor(
    private val repository: NoteRepository,
) : ViewModel() {

    val notes: StateFlow<List<Note>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Testi della nota in modifica: sopravvivono alla rotazione ma non finiscono nello stato salvato (Bundle).
    internal val draft = NoteDrafts()

    fun save(note: Note) {
        viewModelScope.launch { repository.save(note) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }
}

// [key] e' l'id della nota in modifica, null per una nuova.
internal class NoteDraft(val key: Long?, existing: Note?) {
    var title by mutableStateOf(existing?.title ?: "")
    var body by mutableStateOf(existing?.body ?: "")
}

/** La bozza aperta: la stessa finche' si modifica la stessa nota, scartata con [clear] (salva, annulla). */
internal class NoteDrafts {
    private var draft: NoteDraft? = null

    fun draftFor(existing: Note?): NoteDraft {
        val current = draft
        if (current != null && current.key == existing?.id) return current
        return NoteDraft(existing?.id, existing).also { draft = it }
    }

    fun clear() {
        draft = null
    }
}
