package com.pockettravel.app

import kotlinx.coroutines.CancellationException

/** Come [runCatching], ma la cancellazione della coroutine non diventa un errore da gestire: si rilancia. */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    runCatching(block).onFailure { if (it is CancellationException) throw it }
