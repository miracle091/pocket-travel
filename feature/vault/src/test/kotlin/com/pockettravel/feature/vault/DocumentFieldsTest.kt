package com.pockettravel.feature.vault

import com.pockettravel.core.data.DocumentType
import com.pockettravel.core.data.Passport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentFieldsTest {
    private fun document(type: DocumentType) = Passport(
        id = "1",
        fullName = "Nome",
        documentNumber = "N1",
        nationality = "Italiana",
        expiryDate = "2030-01-01",
        documentType = type,
    )

    @Test
    fun `il passaporto richiede nome e numero`() {
        assertFalse(canSaveDocument(DocumentType.PASSPORT, "Mario Rossi", ""))
        assertFalse(canSaveDocument(DocumentType.PASSPORT, "", "AB123"))
        assertTrue(canSaveDocument(DocumentType.PASSPORT, "Mario Rossi", "AB123"))
    }

    @Test
    fun `biglietto e altro richiedono solo il primo campo`() {
        assertTrue(canSaveDocument(DocumentType.TICKET, "Trenitalia", ""))
        assertTrue(canSaveDocument(DocumentType.OTHER, "Assicurazione", ""))
        assertFalse(canSaveDocument(DocumentType.OTHER, " ", "X1"))
    }

    @Test
    fun `altro non mostra nazionalita e data`() {
        val fields = DocumentType.OTHER.fields()
        assertNull(fields.nationality)
        assertNull(fields.expiry)
        assertNotNull(DocumentType.TICKET.fields().nationality)
        assertNotNull(DocumentType.TICKET.fields().expiry)
    }

    @Test
    fun `cambiando tipo si svuotano i campi nascosti`() {
        val cleaned = document(DocumentType.OTHER).withoutHiddenFields(previousType = null)
        assertEquals("", cleaned.nationality)
        assertEquals("", cleaned.expiryDate)
        assertEquals("N1", cleaned.documentNumber)
    }

    @Test
    fun `lo stesso tipo di un documento salvato non perde dati`() {
        val kept = document(DocumentType.OTHER).withoutHiddenFields(previousType = DocumentType.OTHER)
        assertEquals("Italiana", kept.nationality)
        assertEquals("2030-01-01", kept.expiryDate)
    }

    @Test
    fun `biglietto conserva tutti i campi`() {
        val ticket = document(DocumentType.TICKET).withoutHiddenFields(previousType = DocumentType.PASSPORT)
        assertEquals("Italiana", ticket.nationality)
        assertEquals("2030-01-01", ticket.expiryDate)
    }
}
