package com.pockettravel.core.sync

import com.pockettravel.core.data.ExchangeRateTable
import com.pockettravel.core.data.ExchangeRates
import com.pockettravel.core.data.parseExchangeRates
import kotlinx.coroutines.CancellationException
import java.io.IOException
import javax.inject.Inject

/**
 * exchange-rates.json dalla release "app-status" (publish-exchange-rates.yml), con firma e `publishedAt` verificati da
 * [SignedFileCache]: un file non cambiato non si riscarica (ETag). Senza rete, o con un file rifiutato, vale l'ultima
 * copia verificata.
 */
class HttpExchangeRates @Inject constructor(
    private val signedFileCache: SignedFileCache,
) : ExchangeRates {
    override suspend fun latest(): ExchangeRateTable? = try {
        // Validato prima della firma, come online-models.json: un file firmato ma non valido non aggiorna publishedAt.
        signedFileCache.fetch(
            SyncConfig.EXCHANGE_RATES_URL,
            httpError = { throw IOException("Exchange rates fetch failed: HTTP $it") },
            emptyBody = { throw IOException("Empty exchange rates response") },
            parse = ::parseExchangeRates,
        )
    } catch (error: CancellationException) {
        throw error
    } catch (ignored: Exception) {
        signedFileCache.saved(SyncConfig.EXCHANGE_RATES_URL, ::parseExchangeRates)
    }
}
