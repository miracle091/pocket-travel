package com.pockettravel.core.ui

import androidx.compose.runtime.saveable.SaverScope
import com.pockettravel.core.data.GuideTerm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideTermSaverTest {
    private val scope = SaverScope { true }

    @Test
    fun `il termine salvato torna uguale, alias compresi`() {
        val term = GuideTerm("malta", "Ġbejniet", listOf("Gbejniet", "Ġbejna"), "Ġbejna", "Spiegazione.", "https://en.wikipedia.org/wiki/%C4%A0bejna")
        val saved = with(GuideTermSaver) { scope.save(term) }

        assertEquals(term, saved?.let(GuideTermSaver::restore))
    }

    @Test
    fun `nessun foglio aperto non salva niente`() {
        assertNull(with(GuideTermSaver) { scope.save(null) })
    }
}
