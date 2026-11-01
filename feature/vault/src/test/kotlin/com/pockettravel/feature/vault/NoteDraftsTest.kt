package com.pockettravel.feature.vault

import com.pockettravel.core.data.Note
import org.junit.Assert.assertEquals
import org.junit.Test

class NoteDraftsTest {

    private val note = Note(id = 7, title = "Hotel", body = "Camera 12", updatedAt = 0L)

    @Test
    fun `la bozza resta la stessa per la stessa nota`() {
        val drafts = NoteDrafts()
        drafts.draftFor(null).title = "Volo"

        assertEquals("Volo", drafts.draftFor(null).title)
    }

    @Test
    fun `la bozza parte dai dati della nota e cambia con un'altra nota`() {
        val drafts = NoteDrafts()

        assertEquals("Camera 12", drafts.draftFor(note).body)
        assertEquals("", drafts.draftFor(null).title)
    }

    @Test
    fun `clear scarta la bozza`() {
        val drafts = NoteDrafts()
        drafts.draftFor(null).title = "Volo"
        drafts.clear()

        assertEquals("", drafts.draftFor(null).title)
    }
}
