package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BergamotModelsTest {

    @Test
    fun `i sei record sono quelli del registro di Firefox con impronte esadecimali`() {
        val records = BergamotModels.RECORDS

        assertEquals(6, records.size)
        assertEquals(
            listOf(
                "lex.50.50.enit.s2t.bin", "model.enit.intgemm.alphas.bin", "vocab.enit.spm",
                "lex.50.50.iten.s2t.bin", "model.iten.intgemm.alphas.bin", "vocab.iten.spm",
            ),
            records.map { it.fileName },
        )
        val hex = Regex("[0-9a-f]{64}")
        records.forEach {
            assertTrue(it.fileName, hex.matches(it.compressedSha256) && hex.matches(it.sha256))
            assertTrue(it.fileName, Regex("""main-workspace/translations-models-v2/[0-9a-f-]{36}\.zst""").matches(it.attachment))
            assertTrue(it.fileName, it.compressedBytes in 1 until it.bytes)
        }
        assertEquals(records.size, records.map { it.attachment }.toSet().size)
    }

    @Test
    fun `il vocabolario uguale nelle due direzioni si tiene una volta sola`() {
        val names = BergamotModels.FILES.map { it.fileName }

        assertEquals(5, names.size)
        assertEquals(listOf(BergamotModels.VOCABULARY_FILE), names.filter { it.startsWith("vocab.") })
        assertTrue(names.containsAll(listOf(BergamotModels.ENGLISH_TO_ITALIAN_MODEL, BergamotModels.ITALIAN_TO_ENGLISH_MODEL)))
        assertTrue(names.containsAll(listOf(BergamotModels.ENGLISH_TO_ITALIAN_SHORTLIST, BergamotModels.ITALIAN_TO_ENGLISH_SHORTLIST)))
    }

    @Test
    fun `i byte da scaricare e quelli sul dispositivo sono le somme dei cinque file`() {
        assertEquals(1_646_871L + 23_600_729L + 346_942L + 1_878_098L + 22_858_815L, BergamotModels.downloadBytes)
        // 72,8 MB: la coppia misurata nella fase 1.
        assertEquals(4_133_192L + 31_561_787L + 812_724L + 4_713_616L + 31_561_787L, BergamotModels.installedBytes)
    }
}
