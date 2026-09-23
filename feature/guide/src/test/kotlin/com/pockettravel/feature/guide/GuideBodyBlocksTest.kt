package com.pockettravel.feature.guide

import org.junit.Assert.assertEquals
import org.junit.Test

class GuideBodyBlocksTest {
    @Test
    fun `sottosezioni con simbolo o punto e virgola diventano titoli senza simbolo`() {
        val body = "Vini tipici del Titano.\n\n▸ Vini rossi\nSangiovese.\n;Spumanti\n• Moscato\n• Brut"

        assertEquals(
            listOf(
                GuideBodyBlock("Vini tipici del Titano.", isSubheading = false),
                GuideBodyBlock("Vini rossi", isSubheading = true),
                GuideBodyBlock("Sangiovese.", isSubheading = false),
                GuideBodyBlock("Spumanti", isSubheading = true),
                GuideBodyBlock("• Moscato\n• Brut", isSubheading = false),
            ),
            guideBodyBlocks(body),
        )
    }

    @Test
    fun `testo senza sottosezioni resta un solo paragrafo`() {
        assertEquals(
            listOf(GuideBodyBlock("Riga uno.\n\nRiga due.", isSubheading = false)),
            guideBodyBlocks("Riga uno.\n\nRiga due."),
        )
    }
}
