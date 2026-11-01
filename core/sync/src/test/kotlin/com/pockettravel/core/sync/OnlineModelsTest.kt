package com.pockettravel.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OnlineModelsTest {

    private fun entry(id: String, role: String = ONLINE_MODEL_ROLE_CAPABLE, input: Double = 1.0) =
        OnlineModelEntry(id = id, role = role, temperature = true, inputUsdPerMTok = input, outputUsdPerMTok = 1.0)

    @Test
    fun `un elenco con un modello automatico per servizio e' valido`() {
        OnlineModelList(mapOf("CHATGPT" to listOf(entry("a", ONLINE_MODEL_ROLE_AUTO), entry("b")))).validate()
    }

    @Test
    fun `servono un solo modello automatico, nomi non ripetuti e prezzi non negativi`() {
        assertThrows(IllegalArgumentException::class.java) { OnlineModelList(mapOf("X" to listOf(entry("a")))).validate() }
        assertThrows(IllegalArgumentException::class.java) {
            OnlineModelList(mapOf("X" to listOf(entry("a", ONLINE_MODEL_ROLE_AUTO), entry("b", ONLINE_MODEL_ROLE_AUTO)))).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            OnlineModelList(mapOf("X" to listOf(entry("a", ONLINE_MODEL_ROLE_AUTO), entry("a")))).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            OnlineModelList(mapOf("X" to listOf(entry("a", ONLINE_MODEL_ROLE_AUTO, input = -1.0)))).validate()
        }
        assertThrows(IllegalArgumentException::class.java) { OnlineModelList(emptyMap()).validate() }
    }

    @Test
    fun `ruolo e nome del modello sono controllati`() {
        assertThrows(IllegalArgumentException::class.java) { entry("a", role = "altro").validate() }
        assertThrows(IllegalArgumentException::class.java) { entry("a b").validate() }
        assertThrows(IllegalArgumentException::class.java) { entry("").validate() }
    }

    @Test
    fun `otherModels si legge, e senza la chiave e' vuoto`() {
        val providers = """"providers":{"X":[{"id":"a","role":"auto","temperature":true,"inputUsdPerMTok":1,"outputUsdPerMTok":1}]}"""
        assertEquals(emptyMap<String, List<OnlineOtherModel>>(), parseOnlineModels("{$providers}".toByteArray()).otherModels)
        val others = """"otherModels":{"X":[{"id":"b","temperature":false,"inputUsdPerMTok":0.5,"outputUsdPerMTok":2}]}"""
        assertEquals(
            mapOf("X" to listOf(OnlineOtherModel("b", temperature = false, inputUsdPerMTok = 0.5, outputUsdPerMTok = 2.0))),
            parseOnlineModels("{$providers,$others}".toByteArray()).otherModels,
        )
        assertThrows(IllegalArgumentException::class.java) {
            parseOnlineModels("{$providers,${others.replace("\"b\"", "\"b c\"")}}".toByteArray())
        }
    }
}
