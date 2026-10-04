package com.pockettravel.app.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.Spacing

/** Nome della lingua da mostrare: sempre nella propria lingua, come fa Android. */
internal fun AppLanguage.label(): String = when (this) {
    AppLanguage.ITALIAN -> "Italiano"
    AppLanguage.ENGLISH -> "English"
}

/** Scelta della lingua con i radio button: applicata subito (da Android 13 senza ricreare l'activity, prima AppLanguage.set la ricrea). */
@Composable
internal fun LanguageOptions(onChosen: () -> Unit = {}) {
    val context = LocalContext.current
    // Chiave sulla configurazione: la lingua cambia senza ricreare l'activity (configChanges nel manifest).
    val current = remember(LocalConfiguration.current) { AppLanguage.current(context) }
    Column(modifier = Modifier.selectableGroup()) {
        AppLanguage.entries.forEach { language ->
            ListItem(
                leadingContent = { RadioButton(selected = language == current, onClick = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.selectable(selected = language == current, role = Role.RadioButton) {
                    onChosen()
                    if (language != current) AppLanguage.set(context.findActivity(), language)
                },
                content = { Text(language.label()) },
            )
        }
    }
}

/** Scelta della lingua con i chip (onboarding): stessa logica di [LanguageOptions]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LanguageChips() {
    val context = LocalContext.current
    val current = remember(LocalConfiguration.current) { AppLanguage.current(context) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        AppLanguage.entries.forEach { language ->
            FilterChip(
                selected = language == current,
                onClick = { if (language != current) AppLanguage.set(context.findActivity(), language) },
                label = { Text(language.label()) },
                leadingIcon = if (language == current) {
                    { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else null,
            )
        }
    }
}

// Dentro un ModalBottomSheet LocalContext e' un ContextThemeWrapper, non l'activity.
private tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("Nessuna activity")
}
