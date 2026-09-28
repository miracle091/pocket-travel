package com.pockettravel.app.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role

/** Nome della lingua da mostrare: sempre nella propria lingua, come fa Android. */
internal fun AppLanguage.label(): String = when (this) {
    AppLanguage.ITALIAN -> "Italiano"
    AppLanguage.ENGLISH -> "English"
}

/** Scelta della lingua con i radio button: applicata subito (AppLanguage.set ricrea l'activity). */
@Composable
internal fun LanguageOptions(onChosen: () -> Unit = {}) {
    val context = LocalContext.current
    val current = remember { AppLanguage.current(context) }
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

// Dentro un ModalBottomSheet LocalContext e' un ContextThemeWrapper, non l'activity.
private tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("Nessuna activity")
}
