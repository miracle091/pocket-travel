package com.pockettravel.app.widget

import android.content.Context
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.pockettravel.app.R
import com.pockettravel.app.navigation.WidgetScreens
import com.pockettravel.app.regions.RegionTab
import com.pockettravel.app.runCatchingCancellable
import com.pockettravel.app.settings.AppLanguage
import dagger.hilt.android.EntryPointAccessors

/**
 * Widget "Scorciatoie": Assistente (solo con un motore disponibile), Navigatore e Documenti dell'ultima regione aperta. Ogni
 * tocco apre MainActivity con la schermata nell'extra; Documenti passa quindi dallo sblocco biometrico di sempre.
 */
class ShortcutsWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val localized = AppLanguage.wrap(context)
        val loader = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).loader()
        val load = suspend {
            runCatchingCancellable { loader.shortcuts() }
                .onFailure { Log.w(TAG, "Widget Scorciatoie: lettura fallita (${it::class.simpleName})") }
                .getOrDefault(ShortcutsWidgetState(null, shortcutsFor(null, false)))
        }
        val initial = load()
        provideContent {
            // Con la sessione aperta updateAll ridisegna senza rileggere i dati: si rileggono qui, a ogni cambio.
            val state by produceState(initial) { loader.changes.collect { value = load() } }
            GlanceTheme { ShortcutsContent(localized, state) }
        }
    }
}

private const val TAG = "ShortcutsWidget"

class ShortcutsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShortcutsWidget()
}

@Composable
private fun ShortcutsContent(context: Context, state: ShortcutsWidgetState) {
    val size = LocalSize.current
    // Con poco spazio solo le icone (con la descrizione per TalkBack): tre etichette non stanno in 2 celle.
    val showLabels = size.height >= 64.dp && size.width / state.shortcuts.size >= 84.dp
    Row(
        modifier = GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp).padding(6.dp),
    ) {
        state.shortcuts.forEach { shortcut ->
            val label = context.getString(shortcut.label())
            val screen = when (shortcut) {
                Shortcut.ASSISTANT -> state.regionId?.let { WidgetScreens.regionTab(it, RegionTab.AI) }
                // Con una regione il Navigatore della sua pagina, altrimenti quello della barra.
                Shortcut.NAVIGATOR -> state.regionId?.let { WidgetScreens.regionTab(it, RegionTab.NAVIGATION) } ?: WidgetScreens.NAVIGATOR
                Shortcut.DOCUMENTS -> WidgetScreens.DOCUMENTS
            }
            ShortcutButton(label, shortcut.icon(), showLabels, actionStartActivity(openAppIntent(context, screen)))
        }
    }
}

@Composable
private fun RowScope.ShortcutButton(label: String, @DrawableRes icon: Int, showLabel: Boolean, onClick: Action) {
    val colors = GlanceTheme.colors
    Column(
        modifier = GlanceModifier.defaultWeight().fillMaxHeight().padding(horizontal = 2.dp).background(colors.primaryContainer)
            .cornerRadius(16.dp).clickable(onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = label,
            modifier = GlanceModifier.size(24.dp),
            colorFilter = ColorFilter.tint(colors.onPrimaryContainer),
        )
        if (showLabel) Text(text = label, style = TextStyle(color = colors.onPrimaryContainer, fontSize = 12.sp), maxLines = 1)
    }
}

@StringRes
private fun Shortcut.label(): Int = when (this) {
    Shortcut.ASSISTANT -> R.string.widget_shortcut_assistant
    Shortcut.NAVIGATOR -> R.string.widget_shortcut_navigator
    Shortcut.DOCUMENTS -> R.string.widget_shortcut_documents
}

@DrawableRes
private fun Shortcut.icon(): Int = when (this) {
    Shortcut.ASSISTANT -> R.drawable.widget_ic_assistant
    Shortcut.NAVIGATOR -> R.drawable.widget_ic_navigator
    Shortcut.DOCUMENTS -> R.drawable.widget_ic_documents
}
