package com.pockettravel.feature.vault

import com.pockettravel.core.data.crypto.VaultKeyUnreadableException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.IllegalBlockSizeException

class KeyUnreadableTest {
    @Test
    fun tagGcmSbagliatoEUnaChiaveIlleggibile() {
        assertTrue(isKeyUnreadable(AEADBadTagException("tag")))
    }

    @Test
    fun tagGcmSbagliatoComeCausaEUnaChiaveIlleggibile() {
        assertTrue(isKeyUnreadable(RuntimeException("avvolta", AEADBadTagException("tag"))))
    }

    @Test
    fun chiaveDiSessioneAvvoltaMancanteEUnaChiaveIlleggibile() {
        assertTrue(isKeyUnreadable(VaultKeyUnreadableException("mancante")))
    }

    @Test
    fun gliErroriMomentaneiDelKeystoreSiRiprovano() {
        assertFalse(isKeyUnreadable(IllegalBlockSizeException("blocco")))
        assertFalse(isKeyUnreadable(ProviderException("provider", KeyStoreException("keystore"))))
        assertFalse(isKeyUnreadable(IllegalStateException("stato")))
    }
}
