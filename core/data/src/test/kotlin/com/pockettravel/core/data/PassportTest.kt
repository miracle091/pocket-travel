package com.pockettravel.core.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PassportTest {

    @Test
    fun `un JSON senza documentType deserializza con il default PASSPORT`() {
        val legacyJson = """
            {"id":"p1","fullName":"Mario Rossi","documentNumber":"AA1234567","nationality":"IT"}
        """.trimIndent()

        val passport = Json.decodeFromString(Passport.serializer(), legacyJson)

        assertEquals(DocumentType.PASSPORT, passport.documentType)
        assertEquals(emptyList<String>(), passport.photoFileNames)
        assertEquals("", passport.note)
    }

    @Test
    fun `note e photoFileNames sopravvivono a un round-trip di serializzazione`() {
        val passport = Passport(
            id = "p1",
            fullName = "Mario Rossi",
            documentNumber = "AA1234567",
            nationality = "IT",
            note = "Scade a breve",
            photoFileNames = listOf("a.jpg.enc", "b.jpg.enc"),
            documentType = DocumentType.TICKET,
        )

        val roundTripped = Json.decodeFromString(Passport.serializer(), Json.encodeToString(Passport.serializer(), passport))

        assertEquals(passport, roundTripped)
    }
}
