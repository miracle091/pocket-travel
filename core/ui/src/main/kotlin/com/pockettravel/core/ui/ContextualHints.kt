package com.pockettravel.core.ui

import android.content.Context
import android.content.SharedPreferences
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import com.pockettravel.core.data.PreferencesFactory

/** Flag "visto" dei suggerimenti contestuali, nelle preferenze locali (nessun dato personale). */
object ContextualHints {
    private const val PREFS = "contextual_hints"

    fun isSeen(factory: PreferencesFactory, hintId: String): Boolean = isSeen(factory.open(PREFS), hintId)

    fun markSeen(factory: PreferencesFactory, hintId: String) = markSeen(factory.open(PREFS), hintId)

    /** Rimostra tutti i suggerimenti alla prossima apertura delle schermate (Rivedi il tutorial). */
    fun reset(factory: PreferencesFactory) = reset(factory.open(PREFS))

    internal fun isSeen(prefs: SharedPreferences, hintId: String): Boolean = prefs.getBoolean(hintId, false)

    internal fun markSeen(prefs: SharedPreferences, hintId: String) = prefs.edit { putBoolean(hintId, true) }

    internal fun reset(prefs: SharedPreferences) = prefs.edit { clear() }
}

/**
 * Suggerimento mostrato in cima a una schermata solo la prima volta che si apre: resta finche' la schermata e' aperta
 * (anche dopo una rotazione) o finche' non viene chiuso, poi non ricompare. [hintId] identifica il flag "visto".
 */
@Composable
fun ContextualHint(hintId: String, icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val factory = remember(context) { PreferencesFactory { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE) } }
    var visible by rememberSaveable(hintId) { mutableStateOf(!ContextualHints.isSeen(factory, hintId)) }
    LaunchedEffect(hintId) { ContextualHints.markSeen(factory, hintId) }
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
