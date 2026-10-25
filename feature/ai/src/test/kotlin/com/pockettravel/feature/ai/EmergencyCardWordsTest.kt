package com.pockettravel.feature.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyCardWordsTest {

    @Test
    fun `italian life threats show the emergency card`() {
        listOf(
            "Mio padre non respira",
            "Aiuto, ha smesso di respirare!",
            "Mia figlia non riesce a respirare bene",
            "Mio marito è incosciente sul pavimento",
            "Una signora è svenuta in strada",
            "Mia moglie non si sveglia",
            "Perde molto sangue dalla gamba",
            "Credo che sia un infarto",
            "Ha un forte dolore al petto",
            "Abbiamo avuto un incidente in moto",
            "Un bambino sta annegando",
            "Penso sia un'overdose",
            "Ha le convulsioni",
            "Shock anafilattico dopo una puntura",
            "MIO PADRE NON RESPIRA",
        ).forEach { assertTrue(it, isLifeThreatening(it)) }
    }

    @Test
    fun `english life threats show the emergency card`() {
        listOf(
            "My father is not breathing",
            "He stopped breathing",
            "She can't breathe",
            "My friend is unconscious",
            "Someone passed out on the train",
            "He won't wake up",
            "There is heavy bleeding from his arm",
            "I think he's having a heart attack",
            "She has chest pain",
            "We had a car accident",
            "A man was hit by a car",
            "A child is drowning",
            "He is having a seizure",
            "Anaphylactic shock after a sting",
        ).forEach { assertTrue(it, isLifeThreatening(it)) }
    }

    @Test
    fun `travel questions and general topics do not show it`() {
        listOf(
            "Dove dormire a Roma?",
            "Qual è il numero dell'ambulanza?",
            "Ci sono molti incidenti stradali in Italia?",
            "Com'è il clima a Spalato?",
            "Serve il passaporto per entrare?",
            "Si può fare il bagno al mare?",
            "Quali vaccinazioni servono?",
            "Is Lisbon safe at night?",
            "Are road accidents common in Portugal?",
            "How do I call the police?",
            "What is the stroke order of Chinese characters?",
            "Where can I go swimming?",
            "Is the tap water safe to drink?",
        ).forEach { assertFalse(it, isLifeThreatening(it)) }
    }
}
