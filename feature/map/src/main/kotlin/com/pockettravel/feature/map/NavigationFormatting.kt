package com.pockettravel.feature.map

import androidx.annotation.PluralsRes
import androidx.compose.ui.res.pluralStringResource
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import com.pockettravel.core.ui.R as UiR
import androidx.compose.runtime.remember
import java.text.NumberFormat
import kotlin.math.roundToInt

// "1 minuto", "2 minuti": 0 e 1 al singolare, mentre la regola di Android per l'italiano mette lo 0 al plurale.
@Composable
internal fun minutesPlural(@PluralsRes id: Int, minutes: Long): String =
    pluralStringResource(id, if (minutes <= 1) 1 else minutes.toInt(), minutes.toInt())

// Ora di arrivo nel formato del dispositivo (24 ore o AM/PM), da adesso piu' il tempo rimasto.
@Composable
internal fun arrivalTime(remainingSeconds: Double): String {
    val context = LocalContext.current
    val minute = System.currentTimeMillis() / MILLIS_PER_MINUTE + (remainingSeconds / SECONDS_PER_MINUTE).roundToInt()
    return remember(minute) { DateFormat.getTimeFormat(context).format(java.util.Date(minute * MILLIS_PER_MINUTE)) }
}

@Composable
internal fun turnText(instruction: TurnInstruction): String = stringResource(turnTextRes(instruction.type), instruction.roundaboutExit)

/** Il testo dell'indicazione (la rotonda vuole il numero dell'uscita come argomento): schermo, notifica e voce. */
internal fun turnTextRes(type: TurnType): Int = when (type) {
    TurnType.CONTINUE -> R.string.turn_continue
    TurnType.SLIGHT_LEFT -> R.string.turn_slight_left
    TurnType.LEFT -> R.string.turn_left
    TurnType.SHARP_LEFT -> R.string.turn_sharp_left
    TurnType.SLIGHT_RIGHT -> R.string.turn_slight_right
    TurnType.RIGHT -> R.string.turn_right
    TurnType.SHARP_RIGHT -> R.string.turn_sharp_right
    TurnType.KEEP_LEFT -> R.string.turn_keep_left
    TurnType.KEEP_RIGHT -> R.string.turn_keep_right
    TurnType.EXIT_LEFT -> R.string.turn_exit_left
    TurnType.EXIT_RIGHT -> R.string.turn_exit_right
    TurnType.U_TURN -> R.string.turn_u_turn
    TurnType.ROUNDABOUT, TurnType.ROUNDABOUT_LEFT -> R.string.turn_roundabout
    TurnType.ARRIVE -> R.string.turn_arrive
}

@Composable
internal fun turnIcon(type: TurnType): ImageVector = ImageVector.vectorResource(turnIconRes(type))

// Frecce di Material Symbols, come le altre icone dell'app. Uscite = rampe, "mantieni" = biforcazione.
internal fun turnIconRes(type: TurnType): Int = when (type) {
    TurnType.CONTINUE -> UiR.drawable.ms_straight
    TurnType.SLIGHT_LEFT -> UiR.drawable.ms_turn_slight_left
    TurnType.LEFT -> UiR.drawable.ms_turn_left
    TurnType.SHARP_LEFT -> UiR.drawable.ms_turn_sharp_left
    TurnType.SLIGHT_RIGHT -> UiR.drawable.ms_turn_slight_right
    TurnType.RIGHT -> UiR.drawable.ms_turn_right
    TurnType.SHARP_RIGHT -> UiR.drawable.ms_turn_sharp_right
    TurnType.KEEP_LEFT -> UiR.drawable.ms_fork_left
    TurnType.KEEP_RIGHT -> UiR.drawable.ms_fork_right
    TurnType.EXIT_LEFT -> UiR.drawable.ms_ramp_left
    TurnType.EXIT_RIGHT -> UiR.drawable.ms_ramp_right
    TurnType.U_TURN -> UiR.drawable.ms_u_turn_left
    TurnType.ROUNDABOUT -> UiR.drawable.ms_roundabout_right
    TurnType.ROUNDABOUT_LEFT -> UiR.drawable.ms_roundabout_left
    TurnType.ARRIVE -> UiR.drawable.ms_flag
}

internal fun isolatedText(result: RouteResult.Isolated): Int =
    if (result.atStart) R.string.navigation_isolated_start else R.string.navigation_isolated_destination

// Sotto il chilometro a decine di metri, sopra in km con un decimale nel formato della lingua.
@Composable
internal fun distanceText(meters: Double): String = formatDistance(meters) { id, args -> stringResource(id, *args) }

/** La distanza di [distanceLabel] composta con [text] (id della stringa e argomenti): schermo e notifica la scrivono allo stesso modo. */
internal inline fun formatDistance(meters: Double, text: (Int, Array<Any>) -> String): String = when (val label = distanceLabel(meters)) {
    is DistanceLabel.Meters -> text(R.string.navigation_meters, arrayOf(label.value))
    is DistanceLabel.Kilometers -> {
        val format = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1; minimumFractionDigits = 1 }
        text(R.string.navigation_kilometers, arrayOf(format.format(label.value)))
    }
}

@Composable
internal fun durationText(seconds: Double): String =
    formatMinutes(remainingMinutes(seconds)) { id, args -> stringResource(id, *args) }

/** Fuori dalla mappa scaricata resta la mappa del mondo (dati fino a z8): oltre questo zoom non mostra piu' niente. */
private const val OUTSIDE_REGION_MAX_ZOOM = 11.0

// Zoom di inseguimento quando la prossima svolta e' lontana (8 km o piu').
private const val FAR_FOLLOW_ZOOM = 12.0

// Conversioni di unita'.

private const val METERS_PER_KILOMETER = 1_000

/**
 * Zoom che segue la posizione: vicino alla svolta si vede l'incrocio, lontano la strada davanti
 * (in autostrada la prossima svolta puo' essere a decine di km). Fuori dal rettangolo della mappa
 * scaricata ([insideRegion] falso) al massimo [OUTSIDE_REGION_MAX_ZOOM].
 */
internal fun followZoom(distanceToNextMeters: Double, insideRegion: Boolean): Double {
    val zoom = when {
        distanceToNextMeters < 150 -> 17.0
        distanceToNextMeters < 400 -> 16.0
        distanceToNextMeters < METERS_PER_KILOMETER -> 15.0
        distanceToNextMeters < 3_000 -> 14.0
        distanceToNextMeters < 8_000 -> 13.0
        else -> FAR_FOLLOW_ZOOM
    }
    return if (insideRegion) zoom else minOf(zoom, OUTSIDE_REGION_MAX_ZOOM)
}
