package com.pockettravel.feature.ai

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes access to the model file and native engine. */
@Singleton
class AiModelCoordinator @Inject constructor() {
    private val mutex = Mutex()
    suspend fun <T> withModelLock(block: suspend () -> T): T = mutex.withLock { block() }
}
