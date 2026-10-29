package com.pockettravel.feature.vault

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultGateTest {

    @Test
    fun `cassaforte bloccata e biometria sparita passa a non configurata`() {
        assertEquals(GateStatus.NOT_ENROLLED, gateStatusOnResume(GateStatus.LOCKED, canAuthenticate = false))
    }

    @Test
    fun `non configurata con biometria tornata passa a bloccata`() {
        assertEquals(GateStatus.LOCKED, gateStatusOnResume(GateStatus.NOT_ENROLLED, canAuthenticate = true))
    }

    @Test
    fun `da sbloccata lo stato non cambia`() {
        assertEquals(GateStatus.UNLOCKED, gateStatusOnResume(GateStatus.UNLOCKED, canAuthenticate = false))
        assertEquals(GateStatus.UNLOCKED, gateStatusOnResume(GateStatus.UNLOCKED, canAuthenticate = true))
    }

    @Test
    fun `senza cambi di biometria lo stato resta`() {
        assertEquals(GateStatus.LOCKED, gateStatusOnResume(GateStatus.LOCKED, canAuthenticate = true))
        assertEquals(GateStatus.NOT_ENROLLED, gateStatusOnResume(GateStatus.NOT_ENROLLED, canAuthenticate = false))
    }
}
