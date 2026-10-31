package com.pockettravel.feature.vault

import androidx.biometric.BiometricManager

internal enum class GateStatus { NOT_ENROLLED, LOCKED, UNLOCKED }

// Biometria forte assente in modo stabile (nessuna impronta o volto registrati, nessun sensore):
// solo allora vale la spiegazione "blocco schermo non impostato". Gli altri esiti diversi da
// BIOMETRIC_SUCCESS (es. BIOMETRIC_ERROR_HW_UNAVAILABLE) sono transitori e non cambiano lo stato.
internal fun isBiometricMissing(authCode: Int): Boolean =
    authCode == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED || authCode == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE

internal fun initialGateStatus(authCode: Int, isUnlocked: Boolean): GateStatus = when {
    isBiometricMissing(authCode) -> GateStatus.NOT_ENROLLED
    isUnlocked -> GateStatus.UNLOCKED
    else -> GateStatus.LOCKED
}

// Al ritorno in primo piano: la biometria forte puo' essere comparsa (NOT_ENROLLED -> LOCKED) o sparita
// (LOCKED -> NOT_ENROLLED, impronte tolte a cassaforte bloccata: senza, lo sblocco non puo' riuscire).
internal fun gateStatusOnResume(current: GateStatus, authCode: Int): GateStatus = when {
    authCode == BiometricManager.BIOMETRIC_SUCCESS && current == GateStatus.NOT_ENROLLED -> GateStatus.LOCKED
    isBiometricMissing(authCode) && current == GateStatus.LOCKED -> GateStatus.NOT_ENROLLED
    else -> current
}
