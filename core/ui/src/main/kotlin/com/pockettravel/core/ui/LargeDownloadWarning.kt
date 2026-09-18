package com.pockettravel.core.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

// Non un limite tecnico: solo la soglia oltre cui un download (pacchetto regionale, modello IA)
// va confermato esplicitamente prima di partire, invece di scattare al primo tap - condivisa tra
// app/regions e feature/ai cosi' un domani lo stesso avviso resta coerente ovunque nell'app.
const val LARGE_DOWNLOAD_WARNING_BYTES = 100L * 1024 * 1024

// L'avviso ha senso solo sui dati cellulari (dove i MB pesano sul piano tariffario, non sulla
// connessione domestica): su Wi-Fi/ethernet un pacchetto da 500+ MB non e' piu' "rischioso" di uno
// da 10, va scaricato senza fermare l'utente con un'ulteriore conferma. false (nessun avviso) se lo
// stato di rete non e' determinabile: un falso negativo qui significa solo saltare una conferma
// innocua, mai bloccare un download legittimo.
fun isOnCellularNetwork(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
}
