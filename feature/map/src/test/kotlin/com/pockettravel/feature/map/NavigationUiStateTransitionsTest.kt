package com.pockettravel.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Priorita' e soglie delle transizioni di [navigationUiState] e [shouldRecalculate] non coperte da NavigationTest. */
class NavigationUiStateTransitionsTest {
    // Stesso percorso a L di NavigationTest: est lungo il parallelo 45, poi nord.
    private val start = RoutePoint(45.0, 10.0)
    private val corner = RoutePoint(45.0, 10.005)
    private val end = RoutePoint(45.004, 10.005)
    private val route = Route(
        points = listOf(start, corner, end),
        distanceMeters = 838.0,
        durationSeconds = 600.0,
        instructions = listOf(TurnInstruction(TurnType.LEFT, 445.0, 1), TurnInstruction(TurnType.ARRIVE, 0.0, 2)),
    )
    private val tracker = NavigationTracker(route)

    private fun fix(point: RoutePoint = start, timeMillis: Long = NOW) = GpsFix(point.latitude, point.longitude, 5f, timeMillis)

    private fun state(
        permission: Boolean = true,
        gpsEnabled: Boolean = true,
        fix: GpsFix? = fix(),
        calculating: Boolean = false,
        result: RouteResult? = RouteResult.Found(route),
        arrived: Boolean = false,
        startedMillis: Long = NOW,
        progress: Double = 0.0,
    ) = navigationUiState(permission, gpsEnabled, fix, NOW, calculating, result, arrived, startedMillis, progress)

    @Test
    fun `il permesso mancante prevale sul GPS spento e sull'arrivo`() {
        assertEquals(NavigationStatus.NeedsPermission, state(permission = false, gpsEnabled = false, arrived = true, fix = null))
    }

    @Test
    fun `una posizione vecchia esattamente quanto il limite non e' ancora persa`() {
        val atLimit = state(fix = fix(timeMillis = NOW - FIX_MAX_AGE_MILLIS)) as NavigationStatus.Navigating

        assertFalse(atLimit.signalLost)
    }

    @Test
    fun `una posizione con l'ora nel futuro (orologio cambiato) non e' persa`() {
        val future = state(fix = fix(timeMillis = NOW + 60_000)) as NavigationStatus.Navigating

        assertFalse(future.signalLost)
    }

    @Test
    fun `un nuovo calcolo dopo un errore mostra il calcolo, non l'errore`() {
        // "Riprova" azzera il risultato, ma un errore ancora in memoria non deve coprire il calcolo in corso.
        assertEquals(NavigationStatus.Calculating(0, 0.2), state(calculating = true, result = RouteResult.NotFound, progress = 0.2))
    }

    @Test
    fun `un ricalcolo fuori strada non toglie la navigazione, che resta sul percorso trovato`() {
        val navigating = state(calculating = true) as NavigationStatus.Navigating

        assertEquals(route, navigating.route)
    }

    @Test
    fun `la stima dell'avanzamento conta solo mentre si calcola`() {
        assertEquals(NavigationStatus.Calculating(0, 0.0), state(calculating = false, result = null, progress = 0.7, startedMillis = NOW - 30_000))
    }

    @Test
    fun `i secondi del calcolo sono interi e mai negativi`() {
        assertEquals(NavigationStatus.Calculating(0, 0.0), state(calculating = true, result = null, startedMillis = NOW - 999))
        assertEquals(NavigationStatus.Calculating(1, 0.0), state(calculating = true, result = null, startedMillis = NOW - 1_000))
        // Orologio tornato indietro dopo l'inizio del calcolo.
        assertEquals(NavigationStatus.Calculating(0, 0.0), state(calculating = true, result = null, startedMillis = NOW + 5_000))
    }

    @Test
    fun `il messaggio di un errore del motore arriva alla schermata`() {
        val failed = RouteResult.Failed("target island detected for section 0")

        assertEquals(NavigationStatus.Unavailable(failed), state(result = failed))
    }

    @Test
    fun `un errore con la posizione persa torna ad aspettare il segnale`() {
        val stale = fix(timeMillis = NOW - FIX_MAX_AGE_MILLIS - 1)

        assertEquals(NavigationStatus.WaitingForFix, state(fix = stale, result = RouteResult.NotFound))
        assertEquals(NavigationStatus.WaitingForFix, state(fix = stale, result = null, calculating = true))
    }

    @Test
    fun `si ricalcola dopo esattamente l'intervallo, non un millisecondo prima`() {
        val offRoute = tracker.progress(RoutePoint(44.999, 10.0025))

        assertTrue(shouldRecalculate(offRoute, false, NOW - RECALCULATION_INTERVAL_MILLIS, NOW))
        assertFalse(shouldRecalculate(offRoute, false, NOW - RECALCULATION_INTERVAL_MILLIS + 1, NOW))
    }

    @Test
    fun `con la precisione esattamente alla soglia di fuori strada si ricalcola`() {
        val offRoute = tracker.progress(RoutePoint(44.999, 10.0025))
        val atThreshold = NavigationTracker.OFF_ROUTE_METERS.toFloat()

        assertTrue(shouldRecalculate(offRoute, false, 0, NOW, accuracyMeters = atThreshold))
        assertFalse(shouldRecalculate(offRoute, false, 0, NOW, accuracyMeters = atThreshold + 0.5f))
    }

    @Test
    fun `da fermi o sul percorso non si ricalcola, nemmeno dopo molto tempo`() {
        val onRoute = tracker.progress(RoutePoint(45.0, 10.0025))

        assertFalse(shouldRecalculate(onRoute, false, 0, NOW + 3_600_000))
    }

    @Test
    fun `con la regione di partenza assente dall'elenco le regioni restano nell'ordine dell'elenco`() {
        val marino = RoutingRegion("san-marino", MapBounds(12.40, 43.89, 12.52, 43.99))
        val italia = RoutingRegion("italia", MapBounds(6.6, 35.5, 18.5, 47.1))

        assertEquals(
            listOf("italia", "san-marino"),
            navigationRegionIds("altrove", listOf(italia, marino), RoutePoint(44.059, 12.568), RoutePoint(43.9356, 12.4473)),
        )
    }

    @Test
    fun `una regione senza rettangolo che non e' quella di partenza non entra nella navigazione`() {
        val senzaMappa = RoutingRegion("senza-mappa", null)
        val italia = RoutingRegion("italia", MapBounds(6.6, 35.5, 18.5, 47.1))

        assertEquals(listOf("italia"), navigationRegionIds("italia", listOf(senzaMappa, italia), RoutePoint(44.0, 11.0), RoutePoint(44.1, 11.1)))
    }

    @Test
    fun `con partenza e arrivo uguali la linea ha due punti uguali`() {
        val here = RoutePoint(44.0, 11.0)

        assertEquals(listOf(here, here), straightLinePoints(here, here))
    }

    @Test
    fun `senza regioni con la rete stradale ogni percorso esce dalle regioni`() {
        assertTrue(leavesRoutingRegions(emptyList(), RoutePoint(44.0, 11.0), RoutePoint(44.1, 11.1)))
    }

    @Test
    fun `un punto sul bordo del rettangolo, entro il margine, non esce`() {
        val italia = RoutingRegion("italia", MapBounds(6.6, 35.5, 18.5, 47.1))

        assertFalse(leavesRoutingRegions(listOf(italia), RoutePoint(47.1, 18.5), RoutePoint(47.12, 18.54)))
        assertTrue(leavesRoutingRegions(listOf(italia), RoutePoint(47.1, 18.5), RoutePoint(47.2, 18.7)))
    }

    @Test
    fun `i modi di navigazione usano i profili BRouter attesi`() {
        assertEquals(listOf("shortest", "trekking", "car-vario"), RouteProfile.entries.map { it.routingProfile })
        assertFalse(BRouterProfile("trekking").wheelchair)
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
