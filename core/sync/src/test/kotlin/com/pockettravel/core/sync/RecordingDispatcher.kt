package com.pockettravel.core.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/** Esegue sul dispatcher [delegate] e conta i lavori ricevuti: dice se il codice passa dal dispatcher iniettato. */
internal class RecordingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    var dispatched = 0
        private set

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = delegate.isDispatchNeeded(context)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatched++
        delegate.dispatch(context, block)
    }
}
