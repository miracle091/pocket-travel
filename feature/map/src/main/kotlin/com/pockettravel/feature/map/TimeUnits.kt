package com.pockettravel.feature.map

import java.time.LocalDateTime
import java.time.ZoneOffset

internal const val SECONDS_PER_MINUTE = 60
internal const val MINUTES_PER_HOUR = 60
internal const val MILLIS_PER_MINUTE = 60_000L

/** Il minuto (dall'epoca, in UTC: l'ora locale presa come e') come data e ora. */
internal fun minuteToDateTime(minute: Long): LocalDateTime = LocalDateTime.ofEpochSecond(minute * SECONDS_PER_MINUTE, 0, ZoneOffset.UTC)

/** L'inverso di [minuteToDateTime]: i secondi si scartano. */
internal fun dateTimeToMinute(time: LocalDateTime): Long = time.toEpochSecond(ZoneOffset.UTC) / SECONDS_PER_MINUTE

/** I [minutes] come "N min" o "N h M min", composti con [text] (id della stringa e argomenti). */
internal inline fun formatMinutes(minutes: Int, text: (Int, Array<Any>) -> String): String =
    if (minutes < MINUTES_PER_HOUR) {
        text(R.string.navigation_minutes, arrayOf(minutes))
    } else {
        text(R.string.navigation_hours_minutes, arrayOf(minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR))
    }
