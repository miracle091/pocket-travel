package com.pockettravel.app.widget

import com.pockettravel.core.data.EmbassyEntry
import com.pockettravel.core.data.EmergencyNumbers

// Logica pura dei widget "Emergenza" e "Scorciatoie": cosa mostrare, senza Glance e senza Context.
// Niente dati della cassaforte, delle note o della posizione: i widget stanno sulla Home, alla vista di chi ha il telefono in mano.

/** Nome di un numero nel widget "Emergenza". */
enum class EmergencyService { SINGLE, GENERAL, POLICE, AMBULANCE, FIRE }

/** Un numero da chiamare; [services] sono i servizi che lo condividono (Giappone: ambulanza e vigili del fuoco). */
data class EmergencyLine(val services: List<EmergencyService>, val number: String)

/** Ambasciata o consolato della nazionalita' scelta, con il telefono da chiamare. */
data class EmbassyLine(val name: String, val phone: String)

/**
 * I numeri da mostrare. Un numero unico uguale a tutti gli altri (112 in Andorra, 911 negli Stati Uniti) e' una riga sola;
 * altrimenti una riga per numero, con i servizi che lo condividono.
 */
fun EmergencyNumbers.widgetLines(): List<EmergencyLine> {
    val generalNumber = general
    if (generalNumber != null && listOf(police, ambulance, fire).all { it == generalNumber }) {
        return listOf(EmergencyLine(listOf(EmergencyService.SINGLE), generalNumber))
    }
    val labeled = buildList {
        generalNumber?.let { add(EmergencyService.GENERAL to it) }
        add(EmergencyService.POLICE to police)
        add(EmergencyService.AMBULANCE to ambulance)
        add(EmergencyService.FIRE to fire)
    }
    return labeled.groupBy({ it.second }, { it.first }).map { (number, services) -> EmergencyLine(services, number) }
}

/** La prima rappresentanza con un telefono, nell'ordine della Guida: senza telefono il tocco non avrebbe cosa chiamare. */
fun List<EmbassyEntry>.widgetEmbassy(): EmbassyLine? =
    firstNotNullOfOrNull { entry -> entry.phone?.takeIf { it.isNotBlank() }?.let { EmbassyLine(entry.name, it) } }

/** Stato del widget "Emergenza". */
sealed interface EmergencyWidgetState {
    /** Nessuna regione aperta o installata: il widget invita ad aprire l'app. */
    data object NoRegion : EmergencyWidgetState

    /**
     * [lines] vuote: la regione non ha un numero di emergenza centralizzato se [noCentralNumber], altrimenti i dati non ci sono
     * ancora (guide non scaricate o in ricaricamento).
     */
    data class Region(
        val regionId: String,
        val name: String,
        val lines: List<EmergencyLine>,
        val embassy: EmbassyLine?,
        val noCentralNumber: Boolean = false,
    ) : EmergencyWidgetState
}

/** Voci del widget "Scorciatoie". */
enum class Shortcut { ASSISTANT, NAVIGATOR, DOCUMENTS }

/**
 * Le scorciatoie da mostrare, nell'ordine dei pulsanti. L'Assistente serve una regione e un motore disponibile (la stessa
 * disponibilita' della scheda IA); Navigatore e Documenti si aprono anche senza regione.
 */
fun shortcutsFor(regionId: String?, aiAvailable: Boolean): List<Shortcut> = buildList {
    if (regionId != null && aiAvailable) add(Shortcut.ASSISTANT)
    add(Shortcut.NAVIGATOR)
    add(Shortcut.DOCUMENTS)
}

/** Stato del widget "Scorciatoie": [regionId] e' l'ultima regione aperta, se c'e' ancora. */
data class ShortcutsWidgetState(val regionId: String?, val shortcuts: List<Shortcut>)

/**
 * Quante righe di numeri stanno in un widget alto [heightDp]: il nome della regione e i margini ne tolgono lo spazio fisso, e
 * almeno una riga resta sempre.
 */
fun emergencyRowCapacity(heightDp: Int): Int = ((heightDp - FIXED_HEIGHT_DP) / ROW_HEIGHT_DP).coerceAtLeast(1)

private const val FIXED_HEIGHT_DP = 40
private const val ROW_HEIGHT_DP = 26

/** Righe da disegnare in [capacity] posti: prima i numeri, l'ambasciata solo se ne avanza uno. */
fun emergencyRows(lines: List<EmergencyLine>, embassy: EmbassyLine?, capacity: Int): Pair<List<EmergencyLine>, EmbassyLine?> {
    val shownLines = lines.take(capacity)
    val shownEmbassy = embassy.takeIf { shownLines.size < capacity }
    return shownLines to shownEmbassy
}
