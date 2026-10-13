package com.pockettravel.feature.vault

import com.pockettravel.core.data.Passport
import com.pockettravel.core.data.PassportPhotoStore
import com.pockettravel.core.data.PassportRepository
import com.pockettravel.core.data.crypto.VaultKeyEnvelope
import com.pockettravel.core.data.db.PassportDao
import com.pockettravel.core.data.db.PassportEntity
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.createTempDirectory

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

    override suspend fun deleteAll() {
        entities.clear()
        flow.value = emptyList()
    }
}

// Stato di blocco e sblocco di VaultViewModel con repository e busta di chiavi veri; la KEK Keystore e'
// sostituita da una chiave AES software passata a completeUnlock, come farebbe il BiometricPrompt.
@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private val dao = FakePassportDao()
    private val photosDir = createTempDirectory("vault-view-model-test").toFile()
    private val files = InMemoryPreferencesFactory()
    private val kek = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    private val passport = Passport(id = "p1", fullName = "Mario Rossi", documentNumber = "AA1234567", nationality = "IT")

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        photosDir.deleteRecursively()
    }

    private fun newViewModel() = VaultViewModel(
        PassportRepository(dao, PassportPhotoStore(photosDir), dispatcher),
        VaultKeyEnvelope(files),
    )

    private fun encryptionCipher() = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, kek) }

    // Il cipher di decifratura usa l'IV con cui la busta ha avvolto la chiave di sessione.
    private fun decryptionCipher(): Cipher {
        val wrapped = files.open("vault_key_envelope_v2").getString("wrapped_dek", null)
        val iv = Base64.getDecoder().decode(checkNotNull(wrapped).substringBefore(':'))
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(128, iv)) }
    }

    private fun VaultViewModel.unlockFirstTime() {
        val cipher = encryptionCipher()
        completeUnlock(VaultKeyEnvelope.UnlockCipher.GenerateAndWrap(cipher), cipher)
    }

    @Test
    fun `appena creato e' bloccato e senza passaporti`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        backgroundScope.launch { viewModel.passports.collect { } }
        advanceUntilIdle()

        assertFalse(viewModel.isUnlocked)
        assertEquals(emptyList<Passport>(), viewModel.passports.value)
    }

    @Test
    fun `dopo lo sblocco i passaporti salvati compaiono nella lista`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        backgroundScope.launch { viewModel.passports.collect { } }
        viewModel.unlockFirstTime()
        viewModel.save(passport)
        advanceUntilIdle()

        assertTrue(viewModel.isUnlocked)
        assertEquals(listOf(passport), viewModel.passports.value)
    }

    @Test
    fun `il blocco svuota la lista anche se i dati restano nel database`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        backgroundScope.launch { viewModel.passports.collect { } }
        viewModel.unlockFirstTime()
        viewModel.save(passport)
        advanceUntilIdle()
        viewModel.lock()
        advanceUntilIdle()

        assertFalse(viewModel.isUnlocked)
        assertEquals(emptyList<Passport>(), viewModel.passports.value)
        assertNotNull(dao.findById("p1"))
    }

    @Test
    fun `il secondo sblocco rilegge i passaporti con la chiave avvolta la prima volta`() = runTest(dispatcher) {
        val first = newViewModel()
        first.unlockFirstTime()
        first.save(passport)
        advanceUntilIdle()

        val second = newViewModel()
        backgroundScope.launch { second.passports.collect { } }
        val cipher = decryptionCipher()
        second.completeUnlock(VaultKeyEnvelope.UnlockCipher.Unwrap(cipher), cipher)
        advanceUntilIdle()

        assertEquals(listOf(passport), second.passports.value)
    }

    @Test
    fun `una miniatura finita dopo il blocco non entra in cache`() {
        val viewModel = newViewModel()
        viewModel.cacheThumbnail("a.jpg", FakeImageBitmap(4, 4))

        assertNull(viewModel.cachedThumbnail("a.jpg"))
    }

    @Test
    fun `da sbloccato la miniatura si tiene e il blocco la toglie`() {
        val viewModel = newViewModel()
        viewModel.unlockFirstTime()
        viewModel.cacheThumbnail("a.jpg", FakeImageBitmap(4, 4))
        assertNotNull(viewModel.cachedThumbnail("a.jpg"))

        viewModel.lock()

        assertNull(viewModel.cachedThumbnail("a.jpg"))
    }
}
