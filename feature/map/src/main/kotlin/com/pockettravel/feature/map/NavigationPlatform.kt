package com.pockettravel.feature.map

import android.content.Context
import com.pockettravel.core.data.PreferencesFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import javax.inject.Inject

/** Fa partire il servizio in primo piano della navigazione ([NavigationService]); nei test se ne registrano le chiamate. */
fun interface GuidanceServiceStarter {
    fun start()
}

/** Programma e toglie l'avviso "e' ora di partire" ([DepartureReminder]); nei test se ne registrano le chiamate. */
interface DepartureReminderScheduler {
    fun schedule(departure: LocalDateTime, destinationName: String)

    fun cancel()
}

/** Scrive il percorso della navigazione nel log per la simulazione GPS (solo debug); nei test se ne registrano le chiamate. */
fun interface RouteSimulationLog {
    fun log(route: Route)
}

class AndroidGuidanceServiceStarter @Inject constructor(
    @ApplicationContext private val context: Context,
) : GuidanceServiceStarter {
    override fun start() = NavigationService.start(context)
}

class AndroidDepartureReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    preferencesFactory: PreferencesFactory,
) : DepartureReminderScheduler {
    private val store = DepartureReminderStore(preferencesFactory.open(DepartureReminderStore.PREFS))

    override fun schedule(departure: LocalDateTime, destinationName: String) =
        DepartureReminder.schedule(context, store, departure, destinationName)

    override fun cancel() = DepartureReminder.cancel(context, store)
}
