package com.pockettravel.feature.ai

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTextTest {

    private fun plain(s: String) = markdownToDisplayText(s).text

    @Test
    fun grassettoDiventaStile() {
        val r = markdownToDisplayText("il cielo sara' **coperto** domani")
        assertEquals("il cielo sara' coperto domani", r.text)
        val span = r.spanStyles.single()
        assertEquals(FontWeight.Bold, span.item.fontWeight)
        assertEquals("coperto", r.text.substring(span.start, span.end))
    }

    @Test
    fun grassettoConDoppioTrattinoBasso() {
        val r = markdownToDisplayText("uno __due__ tre")
        assertEquals("uno due tre", r.text)
        assertEquals(FontWeight.Bold, r.spanStyles.single().item.fontWeight)
    }

    @Test
    fun grassettoNonChiusoResta() {
        assertEquals("a **b c", plain("a **b c"))
    }

    @Test
    fun titoliPerdonoIlSegno() {
        assertEquals("Titolo\nSottotitolo", plain("# Titolo\n### Sottotitolo"))
    }

    @Test
    fun elencoDiventaPuntoElenco() {
        assertEquals("• uno\n• due\n  • tre\n• quattro", plain("* uno\n- due\n  + tre\n+ quattro"))
    }

    @Test
    fun elencoConGrassetto() {
        val r = markdownToDisplayText("* **Roma**: bella")
        assertEquals("• Roma: bella", r.text)
        assertEquals(FontWeight.Bold, r.spanStyles.single().item.fontWeight)
    }

    @Test
    fun corsivoConAsteriscoESottolineato() {
        val a = markdownToDisplayText("una *parola* qui")
        assertEquals("una parola qui", a.text)
        assertEquals(FontStyle.Italic, a.spanStyles.single().item.fontStyle)
        assertEquals("una parola qui", plain("una _parola_ qui"))
    }

    @Test
    fun moltiplicazioneENomiConTrattinoBassoRestano() {
        assertEquals("2 * 3 = 6 e 4 * 5 = 20", plain("2 * 3 = 6 e 4 * 5 = 20"))
        assertEquals("usa snake_case_name qui", plain("usa snake_case_name qui"))
    }

    @Test
    fun codiceSenzaBacktick() {
        assertEquals("digita ls -l", plain("digita `ls -l`"))
        assertEquals("un ` solo", plain("un ` solo"))
    }

    @Test
    fun fraseDiGemini() {
        val s = "Domani a Città di San Marino il cielo sarà **coperto**, con temperature comprese tra " +
            "**13 °C** (massima) e **8 °C** (minima)."
        val r = markdownToDisplayText(s)
        assertEquals(
            "Domani a Città di San Marino il cielo sarà coperto, con temperature comprese tra " +
                "13 °C (massima) e 8 °C (minima).",
            r.text,
        )
        assertEquals(listOf("coperto", "13 °C", "8 °C"), r.spanStyles.map { r.text.substring(it.start, it.end) })
    }
}
