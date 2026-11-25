package com.pockettravel.core.data.crypto

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pockettravel.core.data.PreferencesFactory
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException
import java.security.KeyStore

/**
 * La KEK della cassaforte con l'Android Keystore vero, senza il prompt biometrico (l'impronta la manda solo il PC, con
 * `adb emu finger touch`): senza biometria nessuna chiave e nessun crash, con la biometria la chiave non cifra senza
 * autenticazione, reset() toglie chiave e chiave di sessione avvolta. Dall'API 30, dove la KEK chiede la biometria forte.
 * Senza un'impronta registrata girano solo i casi "senza biometria", gli altri si saltano.
 */
@RunWith(AndroidJUnit4::class)
class VaultKeyEnvelopeDeviceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val factory = PreferencesFactory { context.getSharedPreferences("test_$it", Context.MODE_PRIVATE) }
    private val envelope = VaultKeyEnvelope(factory)
    private val prefs = factory.open(PREFERENCES_NAME)

    private val hasStrongBiometric: Boolean
        get() = context.getSystemService(BiometricManager::class.java)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    private fun kekExists() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(KEK_ALIAS)

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        envelope.reset()
    }

    @After
    fun tearDown() {
        envelope.reset()
    }

    @Test
    fun senzaBiometria_nessunCipherENessunCrash() {
        assumeTrue(!hasStrongBiometric)
        assertNull(envelope.prepareUnlockCipher())
    }

    @Test
    fun conBiometria_laPrimaVoltaCreaUnaCassaforteNuova() {
        assumeTrue(hasStrongBiometric)
        assertTrue(envelope.prepareUnlockCipher() is VaultKeyEnvelope.UnlockCipher.GenerateAndWrap)
        assertTrue(kekExists())
    }

    @Test
    fun conBiometria_senzaAutenticazioneLaKekNonCifraENonSalvaNiente() {
        assumeTrue(hasStrongBiometric)
        val intent = requireNotNull(envelope.prepareUnlockCipher()) { "Con la biometria la KEK si crea" }
        assertThrows(GeneralSecurityException::class.java) { envelope.completeUnlock(intent, intent.cipher) }
        assertFalse(prefs.contains(KEY_WRAPPED_DEK))
        assertTrue(envelope.prepareUnlockCipher() is VaultKeyEnvelope.UnlockCipher.GenerateAndWrap)
    }

    @Test
    fun conBiometria_unaChiaveAvvoltaSalvataSiSvolge_eResetTornaAUnaCassaforteNuova() {
        assumeTrue(hasStrongBiometric)
        prefs.edit().putString(KEY_WRAPPED_DEK, AesGcmCodec.encode(ByteArray(12), ByteArray(48))).commit()
        assertTrue(envelope.prepareUnlockCipher() is VaultKeyEnvelope.UnlockCipher.Unwrap)

        envelope.reset()

        assertFalse(kekExists())
        assertFalse(prefs.contains(KEY_WRAPPED_DEK))
        assertTrue(envelope.prepareUnlockCipher() is VaultKeyEnvelope.UnlockCipher.GenerateAndWrap)
    }

    private companion object {
        // Gli stessi di VaultKeyEnvelope: il test controlla proprio dove finiscono chiave e chiave avvolta.
        const val KEK_ALIAS = "pocket_travel_vault_kek_v2"
        const val PREFERENCES_NAME = "vault_key_envelope_v2"
        const val KEY_WRAPPED_DEK = "wrapped_dek"
    }
}
