package com.pockettravel.core.data.testing

import com.pockettravel.core.data.crypto.HistoryMasterKey
import com.pockettravel.core.data.db.ConversationDao
import com.pockettravel.core.data.db.ConversationEntity
import com.pockettravel.core.data.db.ConversationTurnEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.InvalidKeyException
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/** [ConversationDao] in memoria: i turni si cancellano con la loro conversazione, come con ForeignKey.CASCADE. */
class FakeConversationDao : ConversationDao {
    val conversations = linkedMapOf<String, ConversationEntity>()
    val turns = mutableListOf<ConversationTurnEntity>()
    // Fa fallire le cancellazioni, come un disco pieno o un database rovinato.
    var failDeletes = false
    private val countFlow = MutableStateFlow(0)

    private fun changed() {
        countFlow.value = conversations.size
    }

    override suspend fun insertConversation(conversation: ConversationEntity) {
        check(conversation.id !in conversations)
        conversations[conversation.id] = conversation
        changed()
    }

    override suspend fun insertTurn(turn: ConversationTurnEntity) {
        check(turn.conversationId in conversations) { "FOREIGN KEY constraint failed" }
        // Come OnConflictStrategy.REPLACE: un id gia' salvato sostituisce quel turno.
        val index = turns.indexOfFirst { it.id == turn.id }
        if (turn.id != 0L && index >= 0) turns[index] = turn else turns += turn.copy(id = turns.size + 1L)
    }

    override suspend fun touch(id: String, at: Long) {
        conversations[id]?.let { conversations[id] = it.copy(updatedAt = at) }
    }

    override suspend fun markArchived(id: String) {
        conversations[id]?.let { conversations[id] = it.copy(archiveCount = it.archiveCount + 1) }
    }

    override suspend fun markResumed(id: String, at: Long) {
        conversations[id]?.let { conversations[id] = it.copy(resumeCount = it.resumeCount + 1, archiveCount = maxOf(it.archiveCount, it.resumeCount + 1), lastResumedAt = at) }
    }

    override suspend fun all(): List<ConversationEntity> = conversations.values.sortedByDescending { it.updatedAt }

    override suspend fun find(id: String): ConversationEntity? = conversations[id]

    override suspend fun turns(conversationId: String): List<ConversationTurnEntity> = turns.filter { it.conversationId == conversationId }

    override fun observeCount(): Flow<Int> = countFlow

    override suspend fun delete(id: String) {
        check(!failDeletes) { "Cancellazione non riuscita" }
        conversations.remove(id)
        turns.removeAll { it.conversationId == id }
        changed()
    }

    override suspend fun deleteUpdatedBefore(cutoff: Long) {
        conversations.values.filter { it.updatedAt < cutoff }.forEach { delete(it.id) }
    }
}

/** Coppia RSA software al posto di quella del Keystore: la biometria e' sempre concessa. [deleted] conta i reset, [invalidated] simula le impronte cambiate. */
class SoftwareHistoryMasterKey : HistoryMasterKey {
    private var pair = newPair()
    var invalidated = false
    var deleted = 0
        private set

    override fun publicKey(): PublicKey = pair.public

    // Stessi parametri OAEP di HistoryKeys (MGF1 con SHA-1, come vuole il Keystore).
    override fun newDecryptionCipher(): Cipher {
        if (invalidated) throw InvalidKeyException("Chiave invalidata")
        return Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply {
            init(Cipher.DECRYPT_MODE, pair.private, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
        }
    }

    override fun delete() {
        deleted++
        pair = newPair()
    }

    private fun newPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_KEY_BITS) }.generateKeyPair()

    private companion object {
        const val RSA_KEY_BITS = 2048
    }
}
