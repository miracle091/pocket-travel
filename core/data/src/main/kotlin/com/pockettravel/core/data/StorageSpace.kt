package com.pockettravel.core.data

import android.os.storage.StorageManager
import java.io.File

/**
 * Spazio che l'app puo' occupare nella cartella: quello libero piu' la cache di altre app che il
 * sistema cancella su richiesta (StorageManager.getAllocatableBytes). Senza StorageManager (test
 * JVM) o se il volume non e' riconosciuto, solo quello libero.
 */
fun File.allocatableBytes(storageManager: StorageManager?): Long =
    storageManager?.let { sm -> runCatching { sm.getAllocatableBytes(sm.getUuidForPath(this)) }.getOrNull() }
        ?: usableSpace

/**
 * Prima di scrivere [bytes] nella cartella, chiede al sistema di liberare la cache che conta in
 * [allocatableBytes] (StorageManager.allocateBytes). Best effort: se non basta, la scrittura
 * fallisce per spazio esaurito. Bloccante, da chiamare fuori dal main thread.
 */
fun File.reserveSpace(storageManager: StorageManager?, bytes: Long) {
    if (storageManager == null || bytes <= 0) return
    runCatching { storageManager.allocateBytes(storageManager.getUuidForPath(this), bytes) }
}
