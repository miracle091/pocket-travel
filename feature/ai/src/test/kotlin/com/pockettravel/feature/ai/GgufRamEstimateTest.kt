package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// GGUF finti con le chiavi dei modelli del catalogo; la lista dei token ha solo l'intestazione.
internal class Gguf {
    private var count = 0
    private val body = ByteArrayOutputStream()

    private fun le(size: Int, put: ByteBuffer.() -> Unit) =
        body.write(ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(put).array())

    private fun str(s: String) {
        val bytes = s.toByteArray()
        le(8) { putLong(bytes.size.toLong()) }
        body.write(bytes)
    }

    fun string(key: String, value: String) = apply { count++; str(key); le(4) { putInt(8) }; str(value) }
    fun u32(key: String, value: Int) = apply { count++; str(key); le(4) { putInt(4) }; le(4) { putInt(value) } }
    fun f32(key: String, value: Float) = apply { count++; str(key); le(4) { putInt(6) }; le(4) { putFloat(value) } }
    fun bools(key: String, values: List<Boolean>) = apply {
        count++; str(key); le(4) { putInt(9) }; le(4) { putInt(7) }; le(8) { putLong(values.size.toLong()) }
        values.forEach { body.write(if (it) 1 else 0) }
    }
    fun strings(key: String, values: List<String>) = apply {
        count++; str(key); le(4) { putInt(9) }; le(4) { putInt(8) }; le(8) { putLong(values.size.toLong()) }
        values.forEach { str(it) }
    }

    // La lista dei token: solo l'intestazione, come se il resto del file non fosse ancora scaricato.
    fun bytes(vocab: Long): ByteArray {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x46554747).putInt(3).putLong(300).putLong(count + 1L).array()
        val tokens = ByteArrayOutputStream().apply {
            val key = "tokenizer.ggml.tokens".toByteArray()
            write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(key.size.toLong()).array())
            write(key)
            write(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(9).putInt(8).putLong(vocab).array())
        }
        return header + body.toByteArray() + tokens.toByteArray()
    }
}

internal fun qwen35(blocks: Int, embedding: Int, nextn: Int = 0) = Gguf()
    .string("general.architecture", "qwen35")
    .string("general.name", "Qwen3.5")
    .u32("qwen35.block_count", blocks)
    .u32("qwen35.embedding_length", embedding)
    .u32("qwen35.attention.head_count", 8)
    .u32("qwen35.attention.head_count_kv", 2)
    .u32("qwen35.attention.key_length", 256)
    .u32("qwen35.attention.value_length", 256)
    .f32("qwen35.rope.freq_base", 1e7f)
    .apply { if (nextn > 0) u32("qwen35.nextn_predict_layers", nextn) }
    .u32("qwen35.ssm.conv_kernel", 4)
    .u32("qwen35.ssm.state_size", 128)
    .u32("qwen35.ssm.group_count", 16)
    .u32("qwen35.ssm.time_step_rank", 16)
    .u32("qwen35.ssm.inner_size", 2048)
    .bools("qwen35.attention.recurrent_layers", List(blocks) { (it + 1) % 4 != 0 })
    .u32("qwen35.full_attention_interval", 4)

// Lettura dei metadati GGUF e stima della RAM, con GGUF finti che copiano le chiavi dei modelli del catalogo. I valori
// attesi sono le dimensioni stampate da llama.cpp (llama-server su CPU, contesto 4096, batch 512, 2026-10-31).
class GgufRamEstimateTest {
    private fun read(bytes: ByteArray) = checkNotNull(GgufMetadata.read(ByteArrayInputStream(bytes)))

    private fun mib(bytes: Long) = bytes / (1024.0 * 1024.0)

    @Test
    fun `Qwen3,5 0,8B, cache di 6 strati, stato di 18, logit del vocabolario`() {
        val meta = read(qwen35(blocks = 24, embedding = 1024).bytes(vocab = 248_320))
        assertEquals("qwen35", meta.architecture)
        assertEquals(248_320L, meta.vocabSize)
        val extra = checkNotNull(estimateRamBytes(meta, fileSizeBytes = 0))
        // llama.cpp: KV 48,00 MiB, stato 19,27 MiB, calcolo 39,02 MiB, uscita 0,95 MiB. Il calcolo e' stimato dall'embedding
        // (36 MiB qui, 90 per Qwen3 4B), da cui la tolleranza.
        assertEquals(48.0 + 19.27 + 39.02 + 0.95, mib(extra), 4.0)
    }

    @Test
    fun `Qwen3,5 2B con lo strato MTP, lo strato in piu' non ha ne' cache ne' stato`() {
        val withMtp = checkNotNull(estimateRamBytes(read(qwen35(blocks = 25, embedding = 2048, nextn = 1).bytes(248_320)), 0))
        val withoutMtp = checkNotNull(estimateRamBytes(read(qwen35(blocks = 24, embedding = 2048).bytes(248_320)), 0))
        assertEquals(withoutMtp, withMtp)
    }

    @Test
    fun `Qwen3 4B, solo attenzione, 36 strati di cache`() {
        val gguf = Gguf()
            .string("general.architecture", "qwen3")
            .u32("qwen3.block_count", 36)
            .u32("qwen3.embedding_length", 2560)
            .u32("qwen3.attention.head_count", 32)
            .u32("qwen3.attention.head_count_kv", 8)
            .u32("qwen3.attention.key_length", 128)
            .u32("qwen3.attention.value_length", 128)
        val extra = checkNotNull(estimateRamBytes(read(gguf.bytes(151_936)), 0))
        // llama.cpp: KV 576,00 MiB, calcolo 86,02 MiB, uscita 0,58 MiB.
        assertEquals(576.0 + 86.02 + 0.58, mib(extra), 10.0)
    }

    // Stime dei GGUF del catalogo (primi 64 KB scaricati il 2026-10-31), in MiB: la fascia e' quella del catalogo.
    @Test
    fun `dalla stima le fasce del catalogo`() {
        val mib = 1024L * 1024
        assertEquals(RamTier.MINIMO, minRamTierFor(637 * mib)) // Qwen3.5 0.8B
        assertEquals(RamTier.MINIMO, minRamTierFor(609 * mib)) // Pocket Travel 0.8B
        assertEquals(RamTier.CONFORTEVOLE, minRamTierFor(1_418 * mib)) // Qwen3.5 2B
        assertEquals(RamTier.CONFORTEVOLE, minRamTierFor(1_356 * mib)) // Pocket Travel 2B
        assertEquals(RamTier.AMPIA, minRamTierFor(3_095 * mib)) // Qwen3 4B
        assertEquals(RamTier.AMPIA, minRamTierFor(3_048 * mib)) // Pocket Travel 4B
        assertEquals(RamTier.PUNTA, minRamTierFor(20_000 * mib))
    }

    @Test
    fun `liste di stringhe prima del tokenizer si saltano`() {
        val gguf = qwen35(24, 1024).strings("general.tags", listOf("travel", "it"))
        assertNotNull(estimateRamBytes(read(gguf.bytes(248_320)), 0))
    }

    @Test
    fun `non GGUF, file troncato e chiavi mancanti danno null`() {
        assertNull(GgufMetadata.read(ByteArrayInputStream("PK\u0003\u0004 zip".toByteArray())))
        val bytes = qwen35(24, 1024).bytes(248_320)
        assertNull(GgufMetadata.read(ByteArrayInputStream(bytes.copyOf(bytes.size / 2))))
        assertNull(GgufMetadata.read(ByteArrayInputStream(bytes), maxBytes = 100))
        val noBlocks = Gguf().string("general.architecture", "llama").bytes(32_000)
        assertNull(GgufMetadata.read(ByteArrayInputStream(noBlocks)))
    }

    // Con i GGUF veri, se ci sono su questo PC (cartella nella variabile GGUF_DIR, cercati anche nelle sottocartelle): la
    // stima entro il 5% della somma di llama.cpp (pesi del file, cache, stato, calcolo, uscita).
    @Test
    fun `GGUF veri`() {
        val dir = System.getenv("GGUF_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val measured = mapOf("qwen3.5-0.8b-travel-it-v11.gguf" to 624.4 - 517.19, "Qwen3-4B-Instruct-2507-Q4_K_M.gguf" to 662.6)
        for ((name, extraMib) in measured) {
            val file = checkNotNull(dir).walk().firstOrNull { it.name == name } ?: continue
            val meta = checkNotNull(file.inputStream().use { GgufMetadata.read(it) }) { name }
            val estimate = mib(checkNotNull(estimateRamBytes(meta, file.length())))
            val expected = mib(file.length()) + extraMib
            assertEquals(name, expected, estimate, expected * 0.05)
        }
    }
}
