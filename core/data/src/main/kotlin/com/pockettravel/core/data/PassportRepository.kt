package com.pockettravel.core.data

import com.pockettravel.core.data.crypto.KeystoreCipher
import com.pockettravel.core.data.db.PassportDao
import com.pockettravel.core.data.db.PassportEntity
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

// Ogni record e' cifrato per intero (JSON serializzato -> AES-256-GCM) invece che campo per
// campo: una sola chiave Keystore per tutti i passaporti, coerente con la scelta di semplicita'
// gia' motivata in KeystoreCipher. requireUserAuthentication=true (a differenza della chiave
// usata da AiSettingsStore) perche' questi dati sono piu' sensibili di una chiave API.
class PassportRepository @Inject constructor(
    private val passportDao: PassportDao,
) {
    private val cipher = KeystoreCipher(keyAlias = KEY_ALIAS, requireUserAuthentication = true)

    fun observeAll(): Flow<List<Passport>> =
        passportDao.observeAll().map { entities -> entities.mapNotNull { it.toDomainOrNull() } }

    suspend fun save(passport: Passport) {
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

    suspend fun delete(id: String) = passportDao.deleteById(id)

    private fun PassportEntity.toDomainOrNull(): Passport? =
        cipher.decrypt(encryptedPayload)?.let { json -> Json.decodeFromString(Passport.serializer(), json) }

    private companion object {
        const val KEY_ALIAS = "pocket_travel_vault_v1"
    }
}
