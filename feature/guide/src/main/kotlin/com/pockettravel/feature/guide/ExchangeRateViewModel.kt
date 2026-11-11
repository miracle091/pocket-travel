package com.pockettravel.feature.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettravel.core.data.ExchangeRateTable
import com.pockettravel.core.data.ExchangeRates
import com.pockettravel.core.data.IndicativeRate
import com.pockettravel.core.data.NationalityPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/** Tassi di cambio, nazionalita' di chi viaggia (da cui la valuta di partenza) e giorno di oggi, per il limite dei 14 giorni. */
data class ExchangeRateState(val table: ExchangeRateTable, val nationality: String?, val today: LocalDate) {
    /** Tasso verso la valuta di [destinationCountry], null se non c'e' o e' troppo vecchio. */
    fun rateTo(destinationCountry: String?): IndicativeRate? = table.rateFor(nationality, destinationCountry, today)
}

/**
 * Tassi di cambio per il fatto rapido "Valuta" della guida: il file scaricato all'apertura (o l'ultima copia, senza
 * rete); null finche' non c'e' un file.
 */
@HiltViewModel
class ExchangeRateViewModel @Inject constructor(
    exchangeRates: ExchangeRates,
    nationalityPreferences: NationalityPreferences,
    clock: Clock,
) : ViewModel() {
    private val table = MutableStateFlow<ExchangeRateTable?>(null)

    val state: StateFlow<ExchangeRateState?> = combine(table, nationalityPreferences.nationality) { rates, nationality ->
        rates?.let { ExchangeRateState(it, nationality, LocalDate.now(clock)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    init {
        // latest() non lancia: senza file resta null e il fatto rapido non mostra il tasso.
        viewModelScope.launch { table.value = exchangeRates.latest() }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
