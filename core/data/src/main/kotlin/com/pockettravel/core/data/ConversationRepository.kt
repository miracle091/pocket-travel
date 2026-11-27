package com.pockettravel.core.data

import android.util.Log
import com.pockettravel.core.data.crypto.HistoryKeys
import com.pockettravel.core.data.crypto.HistoryReader
import com.pockettravel.core.data.crypto.SessionAesCipher
import com.pockettravel.core.data.db.ConversationDao
import com.pockettravel.core.data.db.ConversationEntity
import com.pockettravel.core.data.db.ConversationTurnEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

/** Titolo e regione di una conversazione, cifrati insieme. */
@Serializable
data class ConversationSummary(val title: String, val regionId: String, val regionName: String)

/** Un turno della conversazione: la domanda come scritta e come cercata (unita a quella prima se era un seguito), la risposta. */
@Serializable
data class ConversationTurn(
    val askedQuestion: String,
    val question: String,
    val answer: String,
    val citations: List<String> = emptyList(),
    val showOfficialSourceBanner: Boolean = false,
    val officialSourceUrl: String? = null,
    val modelId: String,
    // Citta' e nazione nominate nella domanda: lo stato della conversazione.
    val cities: List<String> = emptyList(),
    val destination: String? = null,
    val askedAt: Long,
    // Nomi di GuideCategory per le domande suggerite sotto la risposta; stringhe, cosi' un nome non piu' noto si salta
    // (guideCategoryOrNull) senza rendere illeggibile il turno. I turni salvati prima non l'hanno: lista vuota.
    val suggestedQuestionCategories: List<String> = emptyList(),
)

/** Una conversazione dell'elenco della cronologia, gia' decifrata. */
data class SavedConversation(
    val id: String,
    val summary: ConversationSummary,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Una conversazione riaperta: [handle] serve a continuarla. */
class ResumedConversation(
    val handle: ConversationHandle,
    val summary: ConversationSummary,
    val turns: List<ConversationTurn>,
    val createdAt: Long,
    val archiveCount: Int,
    val resumeCount: Int,
)

/** La conversazione aperta: la sua chiave resta in memoria finche' la si continua. */
class ConversationHandle internal constructor(val id: String, internal val cipher: SessionAesCipher)

/**
 * Cronologia delle conversazioni dell'assistente, cifrata (vedi HistoryKeys): si scrive senza sblocco, si legge solo con
 * un [HistoryReader] ottenuto dopo la biometria. La usa solo il motore Sul dispositivo; con il motore Online non si chiama.
 *
 * Le scritture vanno in coda, una alla volta e nell'ordine in cui arrivano, in uno scope proprio: finiscono anche se la
 * schermata che le chiede si chiude, e la prima, che crea le chiavi della cronologia, non blocca chi la chiede.
 */
@Singleton
@Suppress("TooManyFunctions") // letture, scritture in coda e indice di ricerca della stessa cronologia
class ConversationRepository @Inject constructor(
    private val dao: ConversationDao,
    private val keys: HistoryKeys,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    // Una scrittura non riuscita (disco pieno, chiave del Keystore invalidata) si perde: la conversazione resta sulla
    // schermata. I test JVM, senza android.util.Log, la sostituiscono.
    internal var onWriteError: (Throwable) -> Unit = { Log.w(TAG, "Scrittura della cronologia non riuscita", it) }
    private val writes = SerialWrites(ioDispatcher) { onWriteError(it) }

    val count: Flow<Int> = dao.observeCount()

    /** Salva una conversazione nuova con il suo primo turno; [summary] si calcola nella coda (puo' leggere il database). */
    fun start(summary: suspend () -> ConversationSummary, firstTurn: ConversationTurn, now: Long = System.currentTimeMillis()): ConversationHandle {
        val key = keys.newConversationKey()
        val handle = ConversationHandle(UUID.randomUUID().toString(), SessionAesCipher(key))
        writes.enqueue {
            val entity = ConversationEntity(
                id = handle.id,
                wrappedKey = keys.wrap(key),
                encryptedSummary = handle.encrypt(summary()),
                createdAt = now,
                updatedAt = now,
            )
            val turn = ConversationTurnEntity(conversationId = handle.id, encryptedPayload = handle.encrypt(firstTurn), createdAt = now)
            dao.insertConversationWithTurn(entity, turn)
        }
        return handle
    }

    fun append(handle: ConversationHandle, turn: ConversationTurn, now: Long = System.currentTimeMillis()) = writes.enqueue {
        // Cancellata nel frattempo (dalla cronologia o con la cronologia intera): non si ricrea.
        if (dao.find(handle.id) == null) return@enqueue
        dao.insertTurn(ConversationTurnEntity(conversationId = handle.id, encryptedPayload = handle.encrypt(turn), createdAt = now))
        dao.touch(handle.id, now)
        forgetInSearch(handle.id)
    }

    /** [turn] al posto dell'ultimo turno: la stessa domanda rifatta (previsioni aggiornate). */
    fun replaceLast(handle: ConversationHandle, turn: ConversationTurn, now: Long = System.currentTimeMillis()) = writes.enqueue {
        val last = dao.turns(handle.id).lastOrNull() ?: return@enqueue
        dao.insertTurn(last.copy(encryptedPayload = handle.encrypt(turn), createdAt = now))
        dao.touch(handle.id, now)
        forgetInSearch(handle.id)
    }

    /** La conversazione lascia la schermata (nuova conversazione, cambio di motore o di modello, schermata chiusa). */
    fun archive(handle: ConversationHandle) = writes.enqueue { dao.markArchived(handle.id) }

    /** Dopo le scritture in coda: la prima conversazione, appena chiesta, crea le chiavi da sbloccare. */
    suspend fun prepareUnlock(): HistoryKeys.Unlock {
        writes.await()
        return withContext(ioDispatcher) { keys.prepareUnlock() }
    }

    /** Vedi HistoryKeys.completeUnlock. */
    suspend fun completeUnlock(authenticatedCipher: Cipher): HistoryReader =
        withContext(ioDispatcher) { keys.completeUnlock(authenticatedCipher) }

    /** Le conversazioni, dalla piu' recente; quelle che non si decifrano (chiave di un'altra cronologia) si saltano. */
    suspend fun list(reader: HistoryReader): List<SavedConversation> = withContext(ioDispatcher) {
        writes.await()
        dao.all().mapNotNull { entity ->
            val cipher = reader.conversationKey(entity.wrappedKey)?.let(::SessionAesCipher) ?: return@mapNotNull null
            val summary = cipher.decode<ConversationSummary>(entity.encryptedSummary) ?: return@mapNotNull null
            SavedConversation(entity.id, summary, entity.createdAt, entity.updatedAt)
        }
    }

    // L'indice di ricerca vive in RAM con il lettore che l'ha costruito: chi chiude la cronologia lo butta (closeSearch).
    private class SearchSession(val reader: HistoryReader, val index: ConversationSearchIndex = ConversationSearchIndex())

    private var searchSession: SearchSession? = null

    private fun searchIndex(reader: HistoryReader): ConversationSearchIndex = synchronized(this) {
        searchSession?.takeIf { it.reader === reader }?.index ?: SearchSession(reader).also { searchSession = it }.index
    }

    /**
     * Cerca [query] nei testi delle conversazioni (risposte, titoli, domande; non le fonti), solo in memoria: la prima
     * ricerca dopo lo sblocco decifra i turni, le altre rileggono solo le conversazioni cambiate. Risultati in ordine
     * (vedi ConversationSearchIndex.search).
     */
    suspend fun search(reader: HistoryReader, query: String): List<ConversationSearchResult> = withContext(ioDispatcher) {
        writes.await()
        val index = searchIndex(reader)
        val entities = dao.all()
        index.retainOnly(entities.mapTo(HashSet()) { it.id })
        entities.filterNot { index.isCurrent(it.id, it.updatedAt) }.forEach { indexConversation(reader, index, it) }
        index.search(query)
    }

    private suspend fun indexConversation(reader: HistoryReader, index: ConversationSearchIndex, entity: ConversationEntity) {
        val cipher = reader.conversationKey(entity.wrappedKey)?.let(::SessionAesCipher)
        val summary = cipher?.decode<ConversationSummary>(entity.encryptedSummary)
        if (cipher == null || summary == null) {
            index.putUnreadable(entity.id, entity.updatedAt)
            return
        }
        val turns = dao.turns(entity.id).mapNotNull { cipher.decode<ConversationTurn>(it.encryptedPayload) }
        index.put(SavedConversation(entity.id, summary, entity.createdAt, entity.updatedAt), turns)
    }

    /** La cronologia si richiude: l'indice di ricerca, con i testi in chiaro, si butta. */
    fun closeSearch() {
        synchronized(this) { searchSession = null }
    }

    // Una scrittura cambia o toglie una conversazione: la sua voce dell'indice non vale piu'.
    private fun clearSearch() = synchronized(this) { searchSession?.index }?.clear()

    private fun forgetInSearch(id: String) = synchronized(this) { searchSession?.index }?.forget(id)

    /** Riapre una conversazione per continuarla, e conta la ripresa; null se non c'e' piu' o non si decifra. */
    suspend fun resume(reader: HistoryReader, id: String, now: Long = System.currentTimeMillis()): ResumedConversation? = withContext(ioDispatcher) {
        writes.await()
        val entity = dao.find(id) ?: return@withContext null
        val cipher = reader.conversationKey(entity.wrappedKey)?.let(::SessionAesCipher) ?: return@withContext null
        val summary = cipher.decode<ConversationSummary>(entity.encryptedSummary) ?: return@withContext null
        val turns = dao.turns(id).mapNotNull { cipher.decode<ConversationTurn>(it.encryptedPayload) }
        dao.markResumed(id, now)
        ResumedConversation(
            handle = ConversationHandle(id, cipher),
            summary = summary,
            turns = turns,
            createdAt = entity.createdAt,
            archiveCount = maxOf(entity.archiveCount, entity.resumeCount + 1),
            resumeCount = entity.resumeCount + 1,
        )
    }

    /**
     * Cancella una conversazione. Passa dalla coda delle scritture: nessuna scrittura chiesta prima arriva dopo. Lancia se
     * la cancellazione non riesce: la conversazione e' ancora nella cronologia.
     */
    suspend fun delete(id: String) = writes.run {
        dao.delete(id)
        forgetInSearch(id)
    }

    /** Cancella le conversazioni non usate da piu' di [RETENTION_DAYS] giorni (dall'ultimo turno), senza sblocco. */
    fun deleteExpired(now: Long = System.currentTimeMillis()) = writes.enqueue {
        dao.deleteUpdatedBefore(now - RETENTION_DAYS * MS_PER_DAY)
        clearSearch()
    }

    /**
     * Cancella tutta la cronologia e le sue chiavi: senza sblocco, come dalle Impostazioni o con la chiave invalidata. Le
     * chiavi si dimenticano anche se le conversazioni non si cancellano, e in quel caso lancia.
     */
    suspend fun deleteAll() = writes.run {
        try {
            dao.deleteUpdatedBefore(Long.MAX_VALUE)
        } finally {
            keys.reset()
            clearSearch()
        }
    }

    companion object {
        private const val TAG = "ConversationRepository"
        private const val MS_PER_DAY = 24L * 60 * 60 * 1000

        // Copre la preparazione e la durata di un viaggio (all'estero in media 8 notti, Eurostat 2023). Misurati
        // sull'emulatore: circa 10 KB per conversazione di 6 turni, l'elenco di 500 conversazioni si apre in mezzo secondo.
        const val RETENTION_DAYS = 30L
    }
}

/** Scritture in coda: ognuna parte dopo la fine di quella prima, anche se quella si e' interrotta con un errore. */
private class SerialWrites(dispatcher: CoroutineDispatcher, onError: (Throwable) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error -> onError(error) })
    private val lock = Any()
    private var last: Job? = null

    fun enqueue(block: suspend () -> Unit) {
        synchronized(lock) {
            val previous = last
            last = scope.launch {
                previous?.join()
                block()
            }
        }
    }

    /** Una scrittura in coda di cui si aspetta l'esito: lancia l'errore della scrittura. */
    suspend fun run(block: suspend () -> Unit) {
        val done = CompletableDeferred<Unit>()
        enqueue {
            val result = runCatching { block() }
            done.completeWith(result)
            result.getOrThrow()
        }
        done.await()
    }

    /** Aspetta le scritture gia' in coda: un elenco o una cancellazione le vedono tutte. */
    suspend fun await() {
        synchronized(lock) { last }?.join()
    }
}

// Campi aggiunti o tolti in una versione futura non rendono illeggibili i turni salvati.
private val historyJson = Json { ignoreUnknownKeys = true }

private inline fun <reified T> ConversationHandle.encrypt(value: T): String = cipher.encrypt(historyJson.encodeToString(value))

private inline fun <reified T> SessionAesCipher.decode(payload: String): T? =
    decrypt(payload)?.let { runCatching { historyJson.decodeFromString<T>(it) }.getOrNull() }
