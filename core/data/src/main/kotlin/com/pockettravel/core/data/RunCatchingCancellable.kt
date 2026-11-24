package com.pockettravel.core.data

import kotlinx.coroutines.CancellationException

/**
 * Come [runCatching], ma solo per le [Exception]: la cancellazione della coroutine si rilancia, come gli [Error]
 * (memoria esaurita e simili), che non devono diventare un valore vuoto.
 */
@Suppress("TooGenericExceptionCaught") // e' lo scopo della funzione
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
