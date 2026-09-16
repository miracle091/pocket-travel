package com.pockettravel.core.data

import com.pockettravel.core.data.db.PassportDao
import com.pockettravel.core.data.db.PassportEntity
import java.security.SecureRandom
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory, nessun vero database Room: stesso pattern di FakeRegionPackageDao in
 * RegionRepositoryTest. */
private class FakePassportDao : PassportDao {
    private val entities = linkedMapOf<String, PassportEntity>()
    private val flow = MutableStateFlow<List<PassportEntity>>(emptyList())

    override suspend fun upsert(passport: PassportEntity) {
        entities[passport.id] = passport
        flow.value = entities.values.sortedBy { it.createdAt }
    }

    override fun observeAll(): Flow<List<PassportEntity>> = flow

    override suspend fun findById(id: String): PassportEntity? = entities[id]

    override suspend fun deleteById(id: String) {
        entities.remove(id)
        flow.value = entities.values.sortedBy { it.createdAt }
    }
}

class PassportRepositoryTest {

    private fun newRepository(): Pair<PassportRepository, PassportPhotoStore> {
        val photoStore = PassportPhotoStore(createTempDirectory("passport-repository-test").toFile())
        val repository = PassportRepository(FakePassportDao(), photoStore)
        return repository to photoStore
    }

    private fun randomDek(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun samplePassport(note: String = "", photoFileNames: List<String> = emptyList()) = Passport(
        id = "p1",
        fullName = "Mario Rossi",
        documentNumber = "AA1234567",
        nationality = "IT",
        note = note,
        photoFileNames = photoFileNames,
    )

    @Test(expected = IllegalStateException::class)
    fun `save prima di unlock lancia`() = runBlocking {
        val (repository, _) = newRepository()
        repository.save(samplePassport())
    }

    @Test(expected = IllegalStateException::class)
    fun `savePhoto prima di unlock lancia`() {
        val (repository, _) = newRepository()
        runBlocking { repository.savePhoto(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun `save poi observeAll ricostruisce note e photoFileNames`() = runBlocking {
        val (repository, _) = newRepository()
        repository.unlock(randomDek())

        repository.save(samplePassport(note = "Rinnovato nel 2025", photoFileNames = listOf("a.jpg.enc", "b.jpg.enc")))

        val result = repository.observeAll().first().single()
        assertEquals("Rinnovato nel 2025", result.note)
        assertEquals(listOf("a.jpg.enc", "b.jpg.enc"), result.photoFileNames)
    }

    @Test
    fun `dopo lock observeAll non riesce piu' a decifrare i record esistenti`() = runBlocking {
        val (repository, _) = newRepository()
        repository.unlock(randomDek())
        repository.save(samplePassport(note = "segreto"))

        repository.lock()

        assertTrue(repository.observeAll().first().isEmpty())
    }

    @Test
    fun `savePhoto poi loadPhoto ricostruisce i byte originali`() = runBlocking {
        val (repository, _) = newRepository()
        repository.unlock(randomDek())
        val jpegBytes = ByteArray(2048) { (it % 256).toByte() }

        val fileName = repository.savePhoto(jpegBytes)

        assertArrayEquals(jpegBytes, repository.loadPhoto(fileName))
    }

    @Test
    fun `loadPhoto da bloccato restituisce null`() = runBlocking {
        val (repository, _) = newRepository()
        repository.unlock(randomDek())
        val fileName = repository.savePhoto(byteArrayOf(1, 2, 3))

        repository.lock()

        assertNull(repository.loadPhoto(fileName))
    }

    @Test
    fun `deletePhoto funziona anche da bloccato`() = runBlocking {
        val (repository, photoStore) = newRepository()
        repository.unlock(randomDek())
        val fileName = repository.savePhoto(byteArrayOf(1, 2, 3))
        repository.lock()

        repository.deletePhoto(fileName)

        repository.unlock(randomDek())
        assertNull(repository.loadPhoto(fileName))
        assertNull(photoStore.read(fileName))
    }

    @Test
    fun `delete rimuove anche le foto associate al documento`() = runBlocking {
        val (repository, photoStore) = newRepository()
        repository.unlock(randomDek())
        val fileName = repository.savePhoto(byteArrayOf(1, 2, 3))
        repository.save(samplePassport(photoFileNames = listOf(fileName)))

        repository.delete("p1")

        assertTrue(repository.observeAll().first().isEmpty())
        assertNull(photoStore.read(fileName))
    }
}
