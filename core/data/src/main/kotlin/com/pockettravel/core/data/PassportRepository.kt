package com.pockettravel.core.data

import com.pockettravel.core.data.crypto.SessionAesCipher
import com.pockettravel.core.data.db.PassportDao
import com.pockettravel.core.data.db.PassportEntity
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

// Ogni record e' cifrato per intero (JSON serializzato -> AES-256-GCM) invece che campo per
// campo: una sola chiave per tutti i passaporti, coerente con la scelta di semplicita' gia'
// motivata in KeystoreCipher/SessionAesCipher. Le foto (vedi savePhoto/loadPhoto) non entrano nel
// blob JSON: sono file a parte cifrati con la stessa chiave di sessione, referenziati per nome
// da Passport.photoFileNames.
//
// La chiave e' una DEK di sessione (SessionAesCipher), non una chiave Keystore diretta: la UI
// (PassportVaultScreen) la ottiene tramite VaultKeyEnvelope dietro un BiometricPrompt legato a
// CryptoObject e la passa qui con unlock(); lock() la scarta quando la schermata si chiude.
class PassportRepository @Inject constructor(
    private val passportDao: PassportDao,
    private val photoStore: PassportPhotoStore,
) {
    @Volatile
    private var sessionCipher: SessionAesCipher? = null

    fun unlock(dek: ByteArray) {
        sessionCipher = SessionAesCipher(dek)
    }

    fun lock() {
        sessionCipher = null
    }

    fun observeAll(): Flow<List<Passport>> =
        passportDao.observeAll().map { entities -> entities.mapNotNull { it.toDomainOrNull() } }

    suspend fun save(passport: Passport) {
        val cipher = sessionCipher ?: error("Vault bloccato: chiamare unlock() prima di save()")
        val now = System.currentTimeMillis()
        val existing = passportDao.findById(passport.id)
        passportDao.upsert(
            PassportEntity(
                id = passport.id,
                encryptedPayload = cipher.encrypt(Json.encodeToString(Passport.serializer(), passport)),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        passportDao.findById(id)?.toDomainOrNull()?.photoFileNames?.forEach { photoStore.delete(it) }
        passportDao.deleteById(id)
    }

    // jpegBytes deve gia' arrivare ripulito dei tag EXIF sensibili (vedi PassportPhotoCapture nel
    // modulo feature:vault): qui si cifra soltanto.
    suspend fun savePhoto(jpegBytes: ByteArray): String = withContext(Dispatchers.IO) {
        val cipher = sessionCipher ?: error("Vault bloccato: chiamare unlock() prima di savePhoto()")
        val fileName = "${UUID.randomUUID()}.jpg.enc"
        photoStore.write(fileName, cipher.encryptBytes(jpegBytes))
        fileName
    }

    suspend fun loadPhoto(fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        val cipher = sessionCipher ?: return@withContext null
        photoStore.read(fileName)?.let { cipher.decryptBytes(it) }
    }

    // Per scartare una foto scattata ma poi rimossa dalla bozza prima di salvare il documento
    // (vedi PassportEditDialog): non richiede la sessione sbloccata, e' solo I/O sul file.
    suspend fun deletePhoto(fileName: String) = withContext(Dispatchers.IO) { photoStore.delete(fileName) }

    private fun PassportEntity.toDomainOrNull(): Passport? =
        sessionCipher?.decrypt(encryptedPayload)?.let { json -> Json.decodeFromString(Passport.serializer(), json) }
}
