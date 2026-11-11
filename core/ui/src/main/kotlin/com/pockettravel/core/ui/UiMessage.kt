package com.pockettravel.core.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Testo da mostrare deciso dal ViewModel: una risorsa (con argomenti) o un testo gia' pronto. */
sealed interface UiMessage {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiMessage

    data class Plain(val text: String) : UiMessage
}

fun UiMessage.asString(context: Context): String = when (this) {
    is UiMessage.Res -> context.getString(id, *args.toTypedArray())
    is UiMessage.Plain -> text
}

// stringResource e non context.getString: il testo segue la lingua, che cambia senza ricreare l'activity.
@Composable
fun UiMessage.asString(): String = when (this) {
    is UiMessage.Res -> stringResource(id, *args.toTypedArray())
    is UiMessage.Plain -> text
}
