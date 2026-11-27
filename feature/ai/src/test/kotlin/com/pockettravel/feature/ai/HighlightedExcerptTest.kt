package com.pockettravel.feature.ai

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import com.pockettravel.core.data.SearchExcerpt
import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightedExcerptTest {
    private val style = SpanStyle(color = Color.Red)

    @Test
    fun `le parole trovate prendono lo stile, il resto del testo no`() {
        val excerpt = SearchExcerpt("…l'Hôtel Centrale e il treno", listOf(3..7, 23..26))

        val text = highlightedExcerpt(excerpt, style)

        assertEquals(excerpt.text, text.text)
        assertEquals(listOf("Hôtel", "tren"), text.spanStyles.map { text.text.substring(it.start, it.end) })
        assertEquals(listOf(style, style), text.spanStyles.map { it.item })
    }

    @Test
    fun `senza parole trovate il testo resta senza stili`() {
        assertEquals(emptyList<Any>(), highlightedExcerpt(SearchExcerpt("Titolo", emptyList()), style).spanStyles)
    }
}
