package com.pockettravel.feature.map

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

/** Quello che la guida vocale dice di un'indicazione (vedi [VoiceCues]). */
sealed interface VoiceCue {
    /** Dopo una svolta, con la prossima lontana: "Prosegui per 2 chilometri". */
    data class Follow(val meters: Double) : VoiceCue

    /** Il preavviso: "Tra 200 metri, gira a sinistra in via Roma". */
    data class Prepare(val instruction: TurnInstruction, val meters: Double, val street: String?) : VoiceCue

    /** Alla svolta: "Gira a sinistra in via Roma". */
    data class Now(val instruction: TurnInstruction, val street: String?) : VoiceCue

    data object Arrived : VoiceCue
}

/**
 * Quando parlare lungo un percorso: al piu' un preavviso e un "ora" per svolta, l'arrivo una volta, niente fuori percorso.
 * Le soglie crescono con la velocita' in auto, cosi' il preavviso arriva circa [CAR_PREPARE_SECONDS] prima della svolta.
 * Un percorso nuovo (ricalcolo) riparte da capo.
 */
class VoiceCues(private val profile: RouteProfile) {
    private var route: Route? = null
    private var announcedIndex = -1
    private var preparedIndex = -1
    private var nowIndex = -1
    private var arrived = false
    private val speed = SpeedEstimate()

    fun next(route: Route, progress: NavigationProgress, street: String?, timeMillis: Long): VoiceCue? {
        if (route !== this.route) {
            this.route = route
            announcedIndex = -1
            preparedIndex = -1
            nowIndex = -1
        }
        speed.update(progress.remainingMeters, timeMillis * NANOS_PER_MILLI)
        return when {
            progress.offRoute -> null
            progress.arrived -> VoiceCue.Arrived.takeUnless { arrived }.also { arrived = true }
            else -> turnCue(progress, street)
        }
    }

    private fun turnCue(progress: NavigationProgress, street: String?): VoiceCue? {
        val index = progress.nextInstructionIndex
        val instruction = progress.nextInstruction
        val meters = progress.distanceToNextMeters
        val mps = speed.speed ?: typicalSpeedMps(profile)
        val nowAt = nowMeters(profile, mps)
        val prepareAt = prepareMeters(profile, mps)
        val firstSeen = index != announcedIndex
        announcedIndex = index
        return when {
            instruction.type != TurnType.ARRIVE && meters <= nowAt && index != nowIndex -> {
                nowIndex = index
                VoiceCue.Now(instruction, street)
            }
            meters <= prepareAt && meters > nowAt && index != preparedIndex && index != nowIndex -> {
                preparedIndex = index
                VoiceCue.Prepare(instruction, meters, street)
            }
            firstSeen && meters > FOLLOW_FACTOR * prepareAt -> VoiceCue.Follow(meters)
            else -> null
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        // "Prosegui per" solo se la svolta e' ben oltre il preavviso, non pochi metri prima.
        const val FOLLOW_FACTOR = 2
    }
}

private const val WALK_NOW_METERS = 10.0
private const val BIKE_NOW_METERS = 20.0
private const val CAR_NOW_METERS = 30.0
private const val CAR_NOW_SECONDS = 4.0
private const val WALK_PREPARE_METERS = 50.0
private const val BIKE_PREPARE_METERS = 100.0
private const val CAR_PREPARE_METERS = 150.0
private const val CAR_PREPARE_SECONDS = 20.0

internal fun nowMeters(profile: RouteProfile, speedMps: Double): Double = when (profile) {
    RouteProfile.WALK -> WALK_NOW_METERS
    RouteProfile.BIKE -> BIKE_NOW_METERS
    RouteProfile.CAR -> max(CAR_NOW_METERS, speedMps * CAR_NOW_SECONDS)
}

internal fun prepareMeters(profile: RouteProfile, speedMps: Double): Double = when (profile) {
    RouteProfile.WALK -> WALK_PREPARE_METERS
    RouteProfile.BIKE -> BIKE_PREPARE_METERS
    RouteProfile.CAR -> max(CAR_PREPARE_METERS, speedMps * CAR_PREPARE_SECONDS)
}

/** I testi delle risorse, letti dal contesto dell'applicazione (la lingua dell'app) o, nei test, dai file `strings.xml`. */
interface VoiceTexts {
    fun string(@StringRes id: Int, vararg args: Any): String

    fun plural(@PluralsRes id: Int, quantity: Int, vararg args: Any): String

    val locale: Locale
}

class ContextVoiceTexts @Inject constructor(@ApplicationContext private val context: Context) : VoiceTexts {
    override fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    override fun plural(id: Int, quantity: Int, vararg args: Any): String = context.resources.getQuantityString(id, quantity, *args)

    override val locale: Locale get() = context.resources.configuration.locales[0]
}

/** La frase da dire per [cue], nella lingua di [texts]. */
fun voicePhrase(cue: VoiceCue, texts: VoiceTexts): String = when (cue) {
    is VoiceCue.Follow -> texts.string(R.string.voice_follow, spokenDistance(cue.meters, texts))
    is VoiceCue.Prepare -> {
        val turn = if (cue.instruction.type == TurnType.ARRIVE) texts.string(R.string.voice_arrive_ahead) else turnWithStreet(cue.instruction, cue.street, texts)
        texts.string(R.string.voice_in_distance, spokenDistance(cue.meters, texts), turn.replaceFirstChar { it.lowercase(texts.locale) })
    }
    is VoiceCue.Now -> turnWithStreet(cue.instruction, cue.street, texts)
    VoiceCue.Arrived -> texts.string(R.string.voice_arrived)
}

// Alla rotonda la strada dopo "l'uscita 2" si confonderebbe con l'uscita: solo il numero.
private fun turnWithStreet(instruction: TurnInstruction, street: String?, texts: VoiceTexts): String {
    val turn = texts.string(turnTextRes(instruction.type), instruction.roundaboutExit)
    val roundabout = instruction.type == TurnType.ROUNDABOUT || instruction.type == TurnType.ROUNDABOUT_LEFT
    return if (street == null || roundabout) turn else texts.string(R.string.voice_onto_street, turn, street)
}

private const val METERS_PER_KILOMETER = 1000.0
private const val NEAR_STEP_METERS = 10
private const val FAR_STEP_METERS = 50
private const val NEAR_LIMIT_METERS = 100
private const val KILOMETER_STEPS = 2

/**
 * Distanza detta a voce, arrotondata come si direbbe: a 10 m sotto i 100 m, a 50 m fino al chilometro, poi a mezzo
 * chilometro ("1,5 chilometri").
 */
internal fun spokenDistance(meters: Double, texts: VoiceTexts): String {
    val step = if (meters < NEAR_LIMIT_METERS) NEAR_STEP_METERS else FAR_STEP_METERS
    val rounded = max(step, (meters / step).roundToInt() * step)
    if (rounded < METERS_PER_KILOMETER) return texts.plural(R.plurals.voice_meters, rounded, rounded)
    val kilometers = (meters / METERS_PER_KILOMETER * KILOMETER_STEPS).roundToInt().toDouble() / KILOMETER_STEPS
    val whole = kilometers % 1.0 == 0.0
    val number = NumberFormat.getNumberInstance(texts.locale).apply { maximumFractionDigits = 1 }.format(kilometers)
    // I plurali di Android vogliono un intero: un numero con la virgola ("1,5") va al plurale come "2".
    return texts.plural(R.plurals.voice_kilometers, if (whole) kilometers.toInt() else 2, number)
}
