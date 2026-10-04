package com.pockettravel.feature.vault

import androidx.annotation.StringRes
import com.pockettravel.core.data.DocumentType
import com.pockettravel.core.data.Passport

// Etichette del modulo per tipo di documento. L'entita' Passport ha campi fissi (cifrati in un unico
// blob JSON): per Biglietto e Altro si riusano gli stessi campi con un altro significato, senza
// cambiare il formato salvato. Un'etichetta nulla vuol dire campo non mostrato.
//   Biglietto: fullName = compagnia o vettore, documentNumber = codice di prenotazione,
//              nationality = tratta (da - a), expiryDate = data e ora, note = posto e note.
//   Altro:     fullName = titolo, documentNumber = numero o codice facoltativo.
internal data class DocumentFields(
    @StringRes val name: Int,
    @StringRes val number: Int,
    @StringRes val nationality: Int?,
    @StringRes val expiry: Int?,
    @StringRes val note: Int,
    val numberRequired: Boolean,
)

internal fun DocumentType.fields(): DocumentFields = when (this) {
    DocumentType.PASSPORT -> DocumentFields(
        name = R.string.vault_field_name,
        number = R.string.vault_field_number,
        nationality = R.string.vault_field_nationality,
        expiry = R.string.vault_field_expiry,
        note = R.string.vault_field_note,
        numberRequired = true,
    )
    DocumentType.TICKET -> DocumentFields(
        name = R.string.vault_field_ticket_carrier,
        number = R.string.vault_field_ticket_code,
        nationality = R.string.vault_field_ticket_route,
        expiry = R.string.vault_field_ticket_date,
        note = R.string.vault_field_ticket_note,
        numberRequired = false,
    )
    DocumentType.OTHER -> DocumentFields(
        name = R.string.vault_field_other_title,
        number = R.string.vault_field_other_number,
        nationality = null,
        expiry = null,
        note = R.string.vault_field_note,
        numberRequired = false,
    )
}

internal fun canSaveDocument(type: DocumentType, name: String, number: String): Boolean =
    name.isNotBlank() && (number.isNotBlank() || !type.fields().numberRequired)

// Svuota i campi che il tipo scelto non mostra (es. nazionalita' scritta prima di passare ad Altro).
// Un documento gia' salvato dello stesso tipo non viene toccato: conserva i dati che ha.
internal fun Passport.withoutHiddenFields(previousType: DocumentType?): Passport {
    if (previousType == documentType) return this
    val fields = documentType.fields()
    return copy(
        nationality = if (fields.nationality == null) "" else nationality,
        expiryDate = if (fields.expiry == null) "" else expiryDate,
    )
}
