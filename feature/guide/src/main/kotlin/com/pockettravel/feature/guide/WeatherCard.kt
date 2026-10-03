package com.pockettravel.feature.guide

import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.InfoCardHeader
import com.pockettravel.core.ui.PocketTravelTheme
import com.pockettravel.core.ui.R as UiR
import com.pockettravel.core.ui.Spacing
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.TextStyle
import kotlin.math.roundToInt

private const val OPEN_METEO_URL = "https://open-meteo.com/"

// Meteo di un luogo: condizione e temperatura attuali, poi un giorno per colonna con minima, massima e
// probabilita' di pioggia. In fondo l'ora dell'ultimo aggiornamento e la fonte (attribuzione CC BY 4.0), con "Aggiorna"
// ([onRefresh]) acceso solo WEATHER_MIN_REFRESH_MILLIS dopo l'ultimo aggiornamento.
@Composable
internal fun WeatherCard(
    state: PlaceWeather,
    onOpenSource: (url: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
    onRefresh: (() -> Unit)? = null,
) {
    val weather = state.result.weather
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(top = Spacing.l, start = Spacing.l, end = Spacing.l, bottom = Spacing.xs)) {
            InfoCardHeader(
                icon = weather.condition.icon(),
                overline = stringResource(weather.condition.label()),
                title = state.place ?: stringResource(R.string.weather_near_you),
                trailing = { Text(temperatureText(weather.temperature), style = MaterialTheme.typography.headlineMedium) },
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = Spacing.l)) {
                weather.days.forEach { day ->
                    WeatherDayColumn(day, isToday = day.date == weather.today, modifier = Modifier.weight(1f))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.s)) {
                Text(
                    text = stringResource(R.string.weather_footer, updatedText(state.result.updatedAtMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clickable(onClickLabel = stringResource(R.string.weather_source_open)) { onOpenSource(OPEN_METEO_URL, "Open-Meteo") }
                        .wrapContentHeight(Alignment.CenterVertically),
                )
                if (onRefresh != null) {
                    // Si riaccende da solo allo scadere dell'intervallo, senza dover riaprire la guida.
                    val canRefresh by produceState(false, state.result.updatedAtMillis) {
                        val wait = state.result.updatedAtMillis + WEATHER_MIN_REFRESH_MILLIS - System.currentTimeMillis()
                        if (wait > 0) delay(wait)
                        value = true
                    }
                    IconButton(onClick = onRefresh, enabled = canRefresh) {
                        Icon(AppIcons.Refresh, contentDescription = stringResource(R.string.weather_refresh))
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherDayColumn(day: WeatherDay, isToday: Boolean, modifier: Modifier = Modifier) {
    val dayName = if (isToday) {
        stringResource(R.string.weather_today)
    } else {
        day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, LocalLocale.current.platformLocale)
    }
    val condition = stringResource(day.condition.label())
    val max = temperatureText(day.max)
    val min = temperatureText(day.min)
    val description = stringResource(R.string.weather_day_description, dayName, condition, max, min) +
        day.rainChance?.let { stringResource(R.string.weather_rain_description, it) }.orEmpty()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Text(dayName, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Icon(day.condition.icon(), contentDescription = null, modifier = Modifier.size(24.dp))
        Text(max, style = MaterialTheme.typography.bodyMedium)
        Text(min, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Sempre una riga, anche vuota: le colonne restano allineate.
        Text(
            text = day.rainChance?.takeIf { it > 0 }?.let { "$it%" }.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
    }
}

private fun temperatureText(celsius: Double): String = "${celsius.roundToInt()}°"

// "alle 14:32" se oggi, altrimenti con la data: senza rete la scheda puo' mostrare dati di giorni fa.
@Composable
private fun updatedText(millis: Long): String {
    val context = LocalContext.current
    return if (DateUtils.isToday(millis)) {
        stringResource(R.string.weather_updated_today, DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME))
    } else {
        val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME
        stringResource(R.string.weather_updated_on, DateUtils.formatDateTime(context, millis, flags))
    }
}

@StringRes
private fun WeatherCondition.label(): Int = when (this) {
    WeatherCondition.CLEAR -> R.string.weather_clear
    WeatherCondition.PARTLY_CLOUDY -> R.string.weather_partly_cloudy
    WeatherCondition.CLOUDY -> R.string.weather_cloudy
    WeatherCondition.FOG -> R.string.weather_fog
    WeatherCondition.RAIN -> R.string.weather_rain
    WeatherCondition.SNOW -> R.string.weather_snow
    WeatherCondition.THUNDERSTORM -> R.string.weather_thunderstorm
}

@Composable
private fun WeatherCondition.icon(): ImageVector = ImageVector.vectorResource(iconRes())

@DrawableRes
private fun WeatherCondition.iconRes(): Int = when (this) {
    WeatherCondition.CLEAR -> UiR.drawable.ms_sunny
    WeatherCondition.PARTLY_CLOUDY -> UiR.drawable.ms_partly_cloudy_day
    WeatherCondition.CLOUDY -> UiR.drawable.ms_cloud
    WeatherCondition.FOG -> UiR.drawable.ms_foggy
    WeatherCondition.RAIN -> UiR.drawable.ms_rainy
    WeatherCondition.SNOW -> UiR.drawable.ms_weather_snowy
    WeatherCondition.THUNDERSTORM -> UiR.drawable.ms_thunderstorm
}

@Preview
@Composable
private fun WeatherCardPreview() {
    val today = LocalDate.now()
    val conditions = WeatherCondition.entries
    val days = (0 until 7).map { i ->
        WeatherDay(today.plusDays(i.toLong()), conditions[i % conditions.size], 26.0 - i, 16.0 - i / 2.0, if (i % 2 == 0) i * 15 else null)
    }
    PocketTravelTheme {
        WeatherCard(
            state = PlaceWeather(
                place = "Roma",
                result = WeatherResult(
                    Weather(temperature = 22.2, condition = WeatherCondition.PARTLY_CLOUDY, days = days, today = today),
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            ),
            onOpenSource = { _, _ -> },
            modifier = Modifier.padding(Spacing.l),
        )
    }
}
