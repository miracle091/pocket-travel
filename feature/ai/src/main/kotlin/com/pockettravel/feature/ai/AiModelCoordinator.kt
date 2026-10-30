package com.pockettravel.feature.ai

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Serializza l'accesso al file del modello e al codice nativo. */
@Singleton
class AiModelCoordinator @Inject constructor() {
    private val mutex = Mutex()
    suspend fun <T> withModelLock(block: suspend () -> T): T = mutex.withLock { block() }

    /** true mentre un'operazione sul modello (caricamento, generazione, download, eliminazione) tiene il lock. */
    val isLocked: Boolean get() = mutex.isLocked
}
