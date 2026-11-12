package com.pockettravel.app.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
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
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.pockettravel.app.R
import com.pockettravel.app.navigation.WidgetScreens
import com.pockettravel.app.regions.RegionTab
import com.pockettravel.app.runCatchingCancellable
import com.pockettravel.app.settings.AppLanguage
import dagger.hilt.android.EntryPointAccessors

/**
 * Widget "Emergenza": nome dell'ultima regione aperta, numeri di emergenza e ambasciata della nazionalita' scelta. Tocco su un
 * numero: tastierino; tocco sul nome: Guida della regione. Solo dati locali, nessun dato della cassaforte, delle note o della
 * posizione.
 */
class EmergencyWidget : GlanceAppWidget() {
    // La dimensione decide quante righe stanno (emergencyRowCapacity): si ridisegna a ogni ridimensionamento.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Fino ad Android 12 la lingua scelta nell'app non e' quella del contesto del widget.
        val localized = AppLanguage.wrap(context)
        val loader = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).loader()
        val load = suspend {
            runCatchingCancellable { loader.emergency(localized.resources.configuration.locales[0]) }
                .onFailure { Log.w(TAG, "Widget Emergenza: lettura fallita (${it::class.simpleName})") }
                .getOrDefault(EmergencyWidgetState.NoRegion)
        }
        val initial = load()
        provideContent {
            // Con la sessione aperta updateAll ridisegna senza rileggere i dati: si rileggono qui, a ogni cambio.
            val state by produceState(initial) { loader.changes.collect { value = load() } }
            GlanceTheme { EmergencyContent(localized, state) }
        }
    }
}

private const val TAG = "EmergencyWidget"

class EmergencyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EmergencyWidget()
}

@Composable
private fun EmergencyContent(context: Context, state: EmergencyWidgetState) {
    val colors = GlanceTheme.colors
    val root = GlanceModifier.fillMaxSize().appWidgetBackground().background(colors.widgetBackground).cornerRadius(20.dp).padding(8.dp)
    when (state) {
        EmergencyWidgetState.NoRegion -> Column(
            modifier = root.clickable(actionStartActivity(openAppIntent(context))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = context.getString(R.string.widget_open_app),
                style = TextStyle(color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            )
        }
        is EmergencyWidgetState.Region -> Column(modifier = root) {
            Text(
                text = state.name,
                style = TextStyle(color = colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                modifier = GlanceModifier.fillMaxWidth().clickable(
                    actionStartActivity(openAppIntent(context, WidgetScreens.regionTab(state.regionId, RegionTab.GUIDE))),
                ).semantics { contentDescription = context.getString(R.string.widget_open_guide, state.name) },
            )
            Spacer(GlanceModifier.height(4.dp))
            if (state.lines.isEmpty()) {
                Text(
                    text = context.getString(if (state.noCentralNumber) R.string.widget_no_central_number else R.string.widget_numbers_missing),
                    style = TextStyle(color = colors.onSurfaceVariant, fontSize = 13.sp),
                )
            }
            val (lines, embassy) = emergencyRows(state.lines, state.embassy, emergencyRowCapacity(LocalSize.current.height.value.toInt()))
            lines.forEach { line ->
                val label = line.services.joinToString(" / ") { context.getString(it.label()) }
                NumberRow(context, label, line.number, call = actionStartActivity(dialIntent(line.number)))
            }
            embassy?.let { NumberRow(context, it.name, it.phone, call = actionStartActivity(dialIntent(it.phone))) }
        }
    }
}

@Composable
private fun NumberRow(context: Context, label: String, number: String, call: Action) {
    val colors = GlanceTheme.colors
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 1.dp).background(colors.errorContainer).cornerRadius(12.dp)
            .padding(horizontal = 10.dp, vertical = 4.dp).clickable(call)
            .semantics { contentDescription = context.getString(R.string.widget_call, "$label $number") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = TextStyle(color = colors.onErrorContainer, fontSize = 13.sp),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(text = number, style = TextStyle(color = colors.onErrorContainer, fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

private fun EmergencyService.label(): Int = when (this) {
    EmergencyService.SINGLE -> R.string.widget_service_single
    EmergencyService.GENERAL -> R.string.widget_service_general
    EmergencyService.POLICE -> R.string.widget_service_police
    EmergencyService.AMBULANCE -> R.string.widget_service_ambulance
    EmergencyService.FIRE -> R.string.widget_service_fire
}
