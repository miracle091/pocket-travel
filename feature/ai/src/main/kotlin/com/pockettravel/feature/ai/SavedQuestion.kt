package com.pockettravel.feature.ai

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val QUESTION_KEY = "question"

/** La domanda non inviata salvata nello stato del ViewModel, entro [MAX_QUESTION_CHARS]; vuota se manca. */
internal fun SavedStateHandle.savedQuestion(): String = limitedQuestion(get<String>(QUESTION_KEY).orEmpty())

/** Scrive nello stato salvato ogni cambio della domanda, così sopravvive alla morte del processo. */
internal fun SavedStateHandle.keepQuestion(scope: CoroutineScope, state: StateFlow<AiUiState>) {
    scope.launch { state.map { it.question }.distinctUntilChanged().collect { set(QUESTION_KEY, it) } }
}
