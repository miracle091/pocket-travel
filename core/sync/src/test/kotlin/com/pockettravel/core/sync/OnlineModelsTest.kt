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

    @Test
    fun `i servizi di extraProviders hanno id con barre e chiocciola, le chiavi di prima no`() {
        val providers = """"providers":{"X":[{"id":"a","role":"auto","temperature":true,"inputUsdPerMTok":1,"outputUsdPerMTok":1}]}"""
        val extra = """"extraProviders":{"Y":[{"id":"@cf/google/gemma","role":"auto","temperature":true,"inputUsdPerMTok":1,"outputUsdPerMTok":1}]}"""
        val extraOthers = """"extraOtherModels":{"Y":[{"id":"accounts/fireworks/models/glm","temperature":true,"inputUsdPerMTok":1,"outputUsdPerMTok":1}]}"""
        val list = parseOnlineModels("{$providers,$extra,$extraOthers}".toByteArray())
        assertEquals("@cf/google/gemma", list.proposed("Y")?.single()?.id)
        assertEquals("a", list.proposed("X")?.single()?.id)
        assertEquals("accounts/fireworks/models/glm", list.others("Y").single().id)
        assertEquals(emptyList<OnlineOtherModel>(), list.others("X"))
        // Le versioni fino alla 0.14.0 rifiuterebbero il file: in providers e otherModels la regola resta quella di prima.
        assertThrows(IllegalArgumentException::class.java) {
            parseOnlineModels("{${providers.replace("\"a\"", "\"x/a\"")}}".toByteArray())
        }
        listOf("/a", "a/../b", "a//b", "a b").forEach { id ->
            assertThrows(id, IllegalArgumentException::class.java) {
                parseOnlineModels("{$providers,${extra.replace("@cf/google/gemma", id)}}".toByteArray())
            }
        }
        // Uno stesso servizio in tutte e due le chiavi e' un errore.
        assertThrows(IllegalArgumentException::class.java) {
            parseOnlineModels("{$providers,${extra.replace("\"Y\"", "\"X\"")}}".toByteArray())
        }
    }
}
