package com.pockettravel.feature.ai

import com.pockettravel.core.data.GuideCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestedQuestionsTest {

    private val italiano = { topic: SuggestedQuestionTopic, place: String -> "$place: ${topic.name.lowercase()}" }
    private val inglese = { topic: SuggestedQuestionTopic, place: String -> "$place - ${topic.name.lowercase()}?" }

    @Test
    fun unaDomandaPerArgomentoCitato() {
        val result = suggestedQuestions(listOf(GuideCategory.TRASPORTI, GuideCategory.ALLOGGIO), "Roma", asked = emptyList(), text = italiano)
        assertEquals(listOf("Roma: getting_around", "Roma: lodging"), result)
    }

    @Test
    fun ilTestoSegueLaLingua() {
        val result = suggestedQuestions(listOf(GuideCategory.CIBO_BEVANDE), "Rome", asked = emptyList(), text = inglese)
        assertEquals(listOf("Rome - food?"), result)
    }

    @Test
    fun senzaSezioniONessunaDomanda() {
        assertEquals(emptyList<String>(), suggestedQuestions(emptyList(), "Roma", emptyList(), italiano))
        assertEquals(emptyList<String>(), suggestedQuestions(listOf(GuideCategory.CLIMA, GuideCategory.DOGANE), "Roma", emptyList(), italiano))
    }

    @Test
    fun senzaLuogoNessunaDomanda() {
        assertEquals(emptyList<String>(), suggestedQuestions(listOf(GuideCategory.TRASPORTI), null, emptyList(), italiano))
        assertEquals(emptyList<String>(), suggestedQuestions(listOf(GuideCategory.TRASPORTI), " ", emptyList(), italiano))
    }

    @Test
    fun categorieDelloStessoArgomentoNonSiRipetono() {
        val result = suggestedQuestions(listOf(GuideCategory.USI_COSTUMI, GuideCategory.DA_SAPERE), "Roma", emptyList(), italiano)
        assertEquals(listOf("Roma: customs"), result)
    }

    @Test
    fun maiLaDomandaGiaFatta() {
        val result = suggestedQuestions(listOf(GuideCategory.TRASPORTI, GuideCategory.ALLOGGIO), "Roma", listOf(" ROMA: GETTING_AROUND "), italiano)
        assertEquals(listOf("Roma: lodging"), result)
    }

    @Test
    fun alMassimoTre() {
        val all = listOf(GuideCategory.TRASPORTI, GuideCategory.ALLOGGIO, GuideCategory.CIBO_BEVANDE, GuideCategory.COSA_VEDERE)
        assertEquals(3, suggestedQuestions(all, "Roma", emptyList(), italiano).size)
    }
}
