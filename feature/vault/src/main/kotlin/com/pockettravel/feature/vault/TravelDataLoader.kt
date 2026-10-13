package com.pockettravel.feature.vault

import android.content.Context
import android.content.res.AssetManager
import java.io.FileNotFoundException
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class TravelData(val airports: SuggestionIndex, val airlines: SuggestionIndex)

// Carica gli asset solo alla prima apertura del form di un biglietto, fuori dal thread principale, e
// li tiene in memoria per il resto del processo (circa 11 mila voci).
internal object TravelDataLoader {
    @Volatile
    private var cached: TravelData? = null

    suspend fun load(context: Context): TravelData = cached ?: withContext(Dispatchers.IO) {
        val assets = context.applicationContext.assets
        TravelData(
            airports = SuggestionIndex(parseAirports(readLines(assets, "airports.tsv"))),
            airlines = SuggestionIndex(parseAirlines(readLines(assets, "airlines.tsv"))),
        ).also { cached = it }
    }

    // Nel sorgente gli asset sono `<name>.gz`, ma il plugin Android li decomprime e toglie l'estensione:
    // nell'APK c'e' `<name>` in chiaro. Il `.gz` resta come alternativa, se il plugin smette di farlo.
    private fun readLines(assets: AssetManager, name: String): List<String> {
        val stream = try {
            assets.open(name)
        } catch (_: FileNotFoundException) {
            GZIPInputStream(assets.open("$name.gz"))
        }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }
    }
}
