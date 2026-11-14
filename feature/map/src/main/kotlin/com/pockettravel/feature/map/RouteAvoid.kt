package com.pockettravel.feature.map

/**
 * Cosa evitare in un percorso, per modo: ogni opzione e' una variabile del profilo BRouter del modo ([key]=[value], vedi
 * RouteEngine.route). In auto car-vario ha pedaggi, autostrade e sterrati; in bici trekking ha le strade trafficate e i
 * traghetti; a piedi shortest non ne ha.
 */
enum class RouteAvoid(val mode: RouteProfile, val key: String, val value: String) {
    TOLLS(RouteProfile.CAR, "avoid_toll", "1"),
    MOTORWAYS(RouteProfile.CAR, "avoid_motorways", "1"),
    UNPAVED(RouteProfile.CAR, "avoid_unpaved", "1"),
    BUSY_ROADS(RouteProfile.BIKE, "avoid_unsafe", "1"),
    FERRIES(RouteProfile.BIKE, "allow_ferries", "0"),
    ;

    companion object {
        fun forMode(mode: RouteProfile): List<RouteAvoid> = entries.filter { it.mode == mode }

        /** Nomi separati da virgole, per le preferenze; i nomi sconosciuti si saltano. */
        fun encode(avoid: Set<RouteAvoid>): String = avoid.joinToString(",") { it.name }

        fun decode(text: String?): Set<RouteAvoid> =
            text.orEmpty().split(',').mapNotNullTo(mutableSetOf()) { name -> entries.firstOrNull { it.name == name } }
    }
}
