package com.pockettravel.feature.ai

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * I metadati di un GGUF che servono alla stima della RAM, letti dall'inizio del file: architettura, strati, teste,
 * cache e stato ricorrente, e la dimensione del vocabolario. Nei GGUF scritti da llama.cpp le chiavi dell'architettura
 * vengono prima di quelle del tokenizer, nei primi KB del file: la lettura si ferma alla lista dei token, di cui legge
 * solo la lunghezza, cosi' bastano pochi KB anche prima del download (richiesta Range).
 */
class GgufMetadata internal constructor(private val values: Map<String, Any>, val vocabSize: Long) {
    val architecture: String? get() = values["general.architecture"] as? String

    internal fun number(key: String): Long? = when (val value = values["$architecture.$key"]) {
        is Number -> value.toLong()
        // Valori per strato (head_count_kv di alcuni modelli): il massimo, per non sottostimare.
        is List<*> -> value.filterIsInstance<Number>().maxOfOrNull { it.toLong() }
        else -> null
    }

    internal fun flags(key: String): List<Boolean>? = (values["$architecture.$key"] as? List<*>)?.filterIsInstance<Boolean>()

    companion object {
        /** I metadati da [input], o null se non e' un GGUF o se mancano le chiavi della stima entro [maxBytes]. */
        fun read(input: InputStream, maxBytes: Long = DEFAULT_MAX_BYTES): GgufMetadata? = try {
            GgufReader(DataInputStream(LimitedInputStream(input, maxBytes))).read()
        } catch (_: EOFException) {
            null
        }

        // Le chiavi dell'architettura stanno nei primi 2 KB dei GGUF del catalogo (provato il 2026-10-31): 64 KB lasciano
        // spazio ai GGUF con piu' metadati prima del tokenizer.
        const val DEFAULT_MAX_BYTES = 64L * 1024
    }
}

/**
 * Stima della RAM che llama.cpp usa per un modello con i parametri dell'app (contesto [contextSize], batch [batchSize],
 * cache f16): i pesi (la dimensione del file), la cache del contesto degli strati ad attenzione piena, lo stato fisso
 * degli strati ricorrenti (Gated DeltaNet dei Qwen3.5), il buffer di calcolo e quello dei logit.
 * I pesi contano una volta sola: sui telefoni ARM llama.cpp riorganizza quasi tutti i pesi quantizzati in una copia
 * propria (repack) e le pagine del file da cui li ha letti si possono liberare; senza repack i pesi restano pagine del
 * file. Su x86 (emulatore) i Q4_K vengono riorganizzati e le pagine del file restano finche' il sistema non le libera:
 * la PSS misurata li conta due volte.
 * Gli strati MTP (nextn_predict_layers) non hanno cache e llama.cpp non li carica. Null se mancano le chiavi di base.
 */
internal fun estimateRamBytes(meta: GgufMetadata, fileSizeBytes: Long, contextSize: Int = CONTEXT_SIZE, batchSize: Int = BATCH_SIZE): Long? {
    val blocks = meta.number("block_count")
    val embedding = meta.number("embedding_length")
    val heads = meta.number("attention.head_count")
    if (blocks == null || embedding == null || heads == null) return null
    val kvHeads = meta.number("attention.head_count_kv") ?: heads
    val keyLength = meta.number("attention.key_length") ?: (embedding / heads)
    val valueLength = meta.number("attention.value_length") ?: keyLength
    val layers = blocks - (meta.number("nextn_predict_layers") ?: 0)
    val recurrent = meta.flags("attention.recurrent_layers")?.take(layers.toInt())?.count { it }?.toLong()
        ?: meta.number("full_attention_interval")?.takeIf { it > 1 }?.let { layers - layers / it }
        ?: 0L
    val cache = (layers - recurrent) * contextSize * kvHeads * (keyLength + valueLength) * F16_BYTES
    val state = recurrent * recurrentStateBytes(meta)
    val compute = batchSize * embedding * F32_BYTES * COMPUTE_PER_EMBEDDING
    val logits = meta.vocabSize * F32_BYTES
    return fileSizeBytes + cache + state + compute + logits
}

// Stato di uno strato Gated DeltaNet in llama.cpp (f32): la convoluzione (conv_kernel - 1 colonne su inner_size + 2 gruppi
// di state_size) e la matrice state_size x state_size per testa (time_step_rank).
private fun recurrentStateBytes(meta: GgufMetadata): Long {
    val conv = meta.number("ssm.conv_kernel")
    val stateSize = meta.number("ssm.state_size")
    val inner = meta.number("ssm.inner_size")
    if (conv == null || stateSize == null || inner == null) return 0
    val groups = meta.number("ssm.group_count") ?: 1
    val heads = meta.number("ssm.time_step_rank") ?: 1
    return ((conv - 1) * (inner + 2 * groups * stateSize) + stateSize * stateSize * heads) * F32_BYTES
}

/**
 * La fascia di RAM piu' bassa in cui un modello che usa [estimateBytes], con l'app, lascia al sistema e alle altre app
 * almeno [SYSTEM_FREE_BYTES]; PUNTA se nemmeno quella basta. Con le stime dei modelli del catalogo da' le loro fasce
 * (LlmModelDefinition.minRamTier): 0.8B circa 0,6 GiB da 4 GB, 2B circa 1,3 GiB da 6 GB, 4B circa 3 GiB da 8 GB.
 */
internal fun minRamTierFor(estimateBytes: Long): RamTier =
    RamTier.entries.firstOrNull { it.gigabytes > 0 && estimateBytes + APP_BYTES + SYSTEM_FREE_BYTES <= it.gigabytes * GIB }
        ?: RamTier.PUNTA

private const val GIB = 1024L * 1024 * 1024
private const val MIB = 1024L * 1024

// L'app senza il modello (circa 0,4 GB).
private const val APP_BYTES = 380 * MIB

// RAM lasciata a sistema e altre app: tra i 2,6 GiB che il 4B lascerebbe su un telefono da 6 GB (troppo pochi) e i
// 3 GiB che lo 0.8B lascia su uno da 4 GB (abbastanza).
private const val SYSTEM_FREE_BYTES = 2_900 * MIB

private const val CONTEXT_SIZE = 4096 // DEFAULT_CONTEXT_SIZE di ai_chat.cpp
private const val BATCH_SIZE = 512 // BATCH_SIZE di ai_chat.cpp
private const val F16_BYTES = 2L
private const val F32_BYTES = 4L

// Buffer di calcolo di llama.cpp per un batch: circa 18 valori f32 per elemento dell'embedding (misurato su CPU con
// contesto 4096 e batch 512: 39 MiB per Qwen3.5 0.8B, embedding 1024; 86 MiB per Qwen3 4B, embedding 2560).
private const val COMPUTE_PER_EMBEDDING = 18L

private class GgufReader(private val input: DataInputStream) {
    private val buffer = ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    fun read(): GgufMetadata? {
        if (u32() != GGUF_MAGIC) return null
        u32() // versione
        u64() // tensori
        var left = u64()
        val values = HashMap<String, Any>()
        var vocab: Long? = null
        while (vocab == null && left-- > 0) {
            val key = string()
            val type = u32().toInt()
            if (key == TOKENS_KEY && type == TYPE_ARRAY) {
                u32() // tipo degli elementi
                vocab = u64()
            } else {
                value(type, keep = !key.startsWith("tokenizer."))?.let { values[key] = it }
            }
        }
        // Le chiavi dell'architettura vengono prima del tokenizer: qui ci sono gia', e la lista dei token non serve.
        return vocab?.takeIf { values["${values["general.architecture"]}.block_count"] != null }?.let { GgufMetadata(values, it) }
    }

    // Il valore di un tipo GGUF; per le liste lunghe o di stringhe null (si saltano).
    private fun value(type: Int, keep: Boolean): Any? = when (type) {
        TYPE_UINT8, TYPE_INT8 -> input.readUnsignedByte().toLong()
        TYPE_UINT16, TYPE_INT16 -> u16()
        TYPE_UINT32 -> u32()
        TYPE_INT32 -> u32().toInt().toLong()
        TYPE_FLOAT32 -> Float.fromBits(u32().toInt()).toDouble()
        TYPE_BOOL -> input.readUnsignedByte() != 0
        TYPE_STRING -> string()
        TYPE_ARRAY -> array(keep)
        TYPE_UINT64, TYPE_INT64 -> u64()
        TYPE_FLOAT64 -> Double.fromBits(u64())
        else -> throw EOFException("tipo GGUF sconosciuto: $type")
    }

    private fun array(keep: Boolean): List<Any>? {
        val type = u32().toInt()
        val size = u64()
        val small = keep && type != TYPE_STRING && size <= MAX_KEPT_ARRAY
        val items = ArrayList<Any>()
        for (i in 0 until size) {
            val item = value(type, keep = false)
            if (small && item != null) items += item
        }
        return items.takeIf { small }
    }

    private fun string(): String {
        val length = u64()
        if (length > MAX_STRING) throw EOFException("stringa GGUF troppo lunga: $length")
        val bytes = ByteArray(length.toInt())
        input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun u16(): Long = bytes(Short.SIZE_BYTES).short.toUShort().toLong()
    private fun u32(): Long = bytes(Int.SIZE_BYTES).int.toUInt().toLong()
    private fun u64(): Long = bytes(Long.SIZE_BYTES).long

    private fun bytes(n: Int): ByteBuffer {
        buffer.clear()
        input.readFully(buffer.array(), 0, n)
        buffer.limit(n)
        return buffer
    }

    private companion object {
        const val GGUF_MAGIC = 0x46554747L // "GGUF" letto little-endian
        // Tipi dei valori GGUF (gguf_type di ggml).
        const val TYPE_UINT8 = 0
        const val TYPE_INT8 = 1
        const val TYPE_UINT16 = 2
        const val TYPE_INT16 = 3
        const val TYPE_UINT32 = 4
        const val TYPE_INT32 = 5
        const val TYPE_FLOAT32 = 6
        const val TYPE_BOOL = 7
        const val TYPE_STRING = 8
        const val TYPE_ARRAY = 9
        const val TYPE_UINT64 = 10
        const val TYPE_INT64 = 11
        const val TYPE_FLOAT64 = 12
        const val TOKENS_KEY = "tokenizer.ggml.tokens"
        const val MAX_KEPT_ARRAY = 4096L
        const val MAX_STRING = 1L shl 20
    }
}

// Si ferma (EOFException) dopo maxBytes: un GGUF con il tokenizer prima dell'architettura non fa scaricare tutto il file.
private class LimitedInputStream(private val input: InputStream, private var left: Long) : InputStream() {
    override fun read(): Int {
        if (left <= 0) return -1
        return input.read().also { if (it >= 0) left-- }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = input.read(b, off, minOf(len.toLong(), left).toInt())
        if (n > 0) left -= n
        return n
    }

    override fun close() = input.close()
}
