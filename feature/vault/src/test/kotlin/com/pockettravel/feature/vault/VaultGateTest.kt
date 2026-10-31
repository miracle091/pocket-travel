package com.pockettravel.feature.vault

import androidx.biometric.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE
import androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
import androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
import androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
import org.junit.Assert.assertEquals
import org.junit.Test

class VaultGateTest {

    @Test
    fun `cassaforte bloccata e biometria sparita passa a non configurata`() {
        assertEquals(GateStatus.NOT_ENROLLED, gateStatusOnResume(GateStatus.LOCKED, BIOMETRIC_ERROR_NONE_ENROLLED))
        assertEquals(GateStatus.NOT_ENROLLED, gateStatusOnResume(GateStatus.LOCKED, BIOMETRIC_ERROR_NO_HARDWARE))
    }

    @Test
    fun `non configurata con biometria tornata passa a bloccata`() {
        assertEquals(GateStatus.LOCKED, gateStatusOnResume(GateStatus.NOT_ENROLLED, BIOMETRIC_SUCCESS))
    }

    @Test
    fun `da sbloccata lo stato non cambia`() {
        assertEquals(GateStatus.UNLOCKED, gateStatusOnResume(GateStatus.UNLOCKED, BIOMETRIC_ERROR_NONE_ENROLLED))
        assertEquals(GateStatus.UNLOCKED, gateStatusOnResume(GateStatus.UNLOCKED, BIOMETRIC_SUCCESS))
    }

    @Test
    fun `senza cambi di biometria lo stato resta`() {
        assertEquals(GateStatus.LOCKED, gateStatusOnResume(GateStatus.LOCKED, BIOMETRIC_SUCCESS))
        assertEquals(GateStatus.NOT_ENROLLED, gateStatusOnResume(GateStatus.NOT_ENROLLED, BIOMETRIC_ERROR_NONE_ENROLLED))
    }

    @Test
    fun `un errore transitorio non cambia lo stato`() {
        assertEquals(GateStatus.LOCKED, gateStatusOnResume(GateStatus.LOCKED, BIOMETRIC_ERROR_HW_UNAVAILABLE))
        assertEquals(GateStatus.NOT_ENROLLED, gateStatusOnResume(GateStatus.NOT_ENROLLED, BIOMETRIC_ERROR_HW_UNAVAILABLE))
    }

    @Test
    fun `allo start un errore transitorio non mostra blocco schermo non impostato`() {
        assertEquals(GateStatus.LOCKED, initialGateStatus(BIOMETRIC_ERROR_HW_UNAVAILABLE, isUnlocked = false))
        assertEquals(GateStatus.NOT_ENROLLED, initialGateStatus(BIOMETRIC_ERROR_NONE_ENROLLED, isUnlocked = false))
        assertEquals(GateStatus.UNLOCKED, initialGateStatus(BIOMETRIC_SUCCESS, isUnlocked = true))
    }
}
