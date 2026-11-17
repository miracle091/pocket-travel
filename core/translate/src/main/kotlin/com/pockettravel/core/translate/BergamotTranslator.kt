package com.pockettravel.core.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Traduttore sul dispositivo (bergamot-translator, il motore di Firefox Translations): un modello per una
 * direzione di traduzione, per esempio italiano verso inglese. Si ottiene con [load] e si libera con [close].
 *
 * Le traduzioni girano una alla volta, fuori dal thread chiamante. [close] non aspetta mai una traduzione in corso:
 * la segna chiusa e il modello lo libera chi sta traducendo, appena finisce (subito se nessuno traduce).
 */
class BergamotTranslator private constructor(private var handle: Long) : AutoCloseable {

    private val lock = ReentrantLock()

    @Volatile
    private var closing = false

    /** Traduce [text] (anche piu' frasi) e ne restituisce la traduzione; il testo vuoto resta vuoto. */
    suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        lock.withLock {
            check(!closing && handle != 0L) { "Traduttore gia' chiuso" }
            try {
                val translated = BergamotNative.translate(handle, text.toByteArray(Charsets.UTF_8))
                    ?: throw IOException("Traduzione fallita")
                String(translated, Charsets.UTF_8)
            } finally {
                if (closing) releaseLocked()
            }
        }
    }

    override fun close() {
        closing = true
        if (lock.tryLock()) {
            try {
                releaseLocked()
            } finally {
                lock.unlock()
            }
        }
    }

    // Da chiamare con il lock preso.
    private fun releaseLocked() {
        if (handle != 0L) {
            BergamotNative.release(handle)
            handle = 0L
        }
    }

    companion object {
        /**
         * Carica il modello da file locali: [model] (.bin quantizzato), [vocabulary] (.spm, uno solo per origine e
         * destinazione) e, se c'e', [shortlist] (lex...bin). Lancia [IOException] se un file manca o non e' valido:
         * il caricamento traduce una parola di prova, quindi un file rotto fallisce qui e non alla prima traduzione.
         */
        suspend fun load(model: File, vocabulary: File, shortlist: File? = null): BergamotTranslator {
            // Il traduttore creato dentro withContext andrebbe perso, senza close, se la coroutine viene annullata
            // proprio mentre il blocco finisce: lo si tiene fuori per chiuderlo.
            var created: BergamotTranslator? = null
            try {
                return withContext(Dispatchers.IO) {
                    checkFiles(model, vocabulary, shortlist)
                    val handle = BergamotNative.load(
                        model.absolutePath.toByteArray(Charsets.UTF_8),
                        vocabulary.absolutePath.toByteArray(Charsets.UTF_8),
                        shortlist?.absolutePath?.toByteArray(Charsets.UTF_8),
                    )
                    if (handle == 0L) throw IOException("Caricamento del modello fallito: ${model.name}")
                    BergamotTranslator(handle).also { created = it }
                }
            } catch (e: CancellationException) {
                created?.close()
                throw e
            }
        }
    }
}

// Marian sceglie il formato dal suffisso: con un altro abortisce.
private fun checkFiles(model: File, vocabulary: File, shortlist: File?) {
    for (file in listOfNotNull(model, vocabulary, shortlist)) {
        if (!file.isFile) throw IOException("File del modello mancante: ${file.name}")
    }
    checkSuffixes(model, vocabulary)
}

private fun checkSuffixes(model: File, vocabulary: File) {
    if (!model.name.endsWith(".bin")) throw IOException("Il modello deve essere un file .bin: ${model.name}")
    if (!vocabulary.name.endsWith(".spm")) throw IOException("Il vocabolario deve essere un file .spm: ${vocabulary.name}")
}
