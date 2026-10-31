package com.pockettravel.feature.vault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pockettravel.core.data.Passport

// Testi in modifica nel modulo del documento. Vivono nel ViewModel: sopravvivono alla ricreazione
// dell'activity (rotazione, tema, dimensione del testo) ma non finiscono nello stato salvato (Bundle).
// [key] e' l'id del documento in modifica, null per uno nuovo.
internal class DocumentDraft(val key: String?, existing: Passport?) {
    var fullName by mutableStateOf(existing?.fullName ?: "")
    var documentNumber by mutableStateOf(existing?.documentNumber ?: "")
    var nationality by mutableStateOf(existing?.nationality ?: "")
    var expiryDate by mutableStateOf(existing?.expiryDate ?: "")
    var note by mutableStateOf(existing?.note ?: "")
}

/** La bozza aperta: la stessa finche' si modifica lo stesso documento, scartata con [clear] (salva, annulla, blocco). */
internal class DocumentDrafts {
    private var draft: DocumentDraft? = null

    fun draftFor(existing: Passport?): DocumentDraft {
        val current = draft
        if (current != null && current.key == existing?.id) return current
        return DocumentDraft(existing?.id, existing).also { draft = it }
    }

    fun clear() {
        draft = null
    }
}
