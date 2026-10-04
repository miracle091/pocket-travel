package com.pockettravel.core.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/** Flag "visto" dei suggerimenti contestuali, nelle preferenze locali (nessun dato personale). */
object ContextualHints {
    private const val PREFS = "contextual_hints"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSeen(context: Context, hintId: String): Boolean = prefs(context).getBoolean(hintId, false)

    fun markSeen(context: Context, hintId: String) = prefs(context).edit().putBoolean(hintId, true).apply()

    /** Rimostra tutti i suggerimenti alla prossima apertura delle schermate (Rivedi il tutorial). */
    fun reset(context: Context) = prefs(context).edit().clear().apply()
}

/**
 * Suggerimento mostrato in cima a una schermata solo la prima volta che si apre: resta finche' la schermata e' aperta
 * (anche dopo una rotazione) o finche' non viene chiuso, poi non ricompare. [hintId] identifica il flag "visto".
 */
@Composable
fun ContextualHint(hintId: String, icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var visible by rememberSaveable(hintId) { mutableStateOf(!ContextualHints.isSeen(context, hintId)) }
    LaunchedEffect(hintId) { ContextualHints.markSeen(context, hintId) }
    AnimatedVisibility(visible = visible) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
        ) {
            Column(modifier = Modifier.padding(InfoCardDefaults.CardPadding)) {
                InfoCardHeader(icon = icon, title = title, iconTint = MaterialTheme.colorScheme.onSecondaryContainer)
                CardDescription(text = body, modifier = Modifier.padding(top = Spacing.s))
                TextButton(onClick = { visible = false }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.hint_dismiss))
                }
            }
        }
    }
}
