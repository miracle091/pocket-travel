package com.pockettravel.core.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException

/** Perché una richiesta di rete è fallita subito pur con una rete che il sistema dà per funzionante. */
enum class NetworkBlock {
    NONE,

    /** Il Risparmio dati di Android limita la rete dell'app. */
    DATA_SAVER,

    /** Probabile blocco sulla sola app (firewall del telefono, rete in background negata): non si può confermare. */
    SUSPECTED,
}

/**
 * Classifica [error] di una richiesta di rete. Solo gli errori immediati (host non risolto, connessione rifiutata)
 * con una rete [networkValidated] contano, anche come causa di un altro errore: timeout ed errori HTTP dicono altro, e
 * senza rete il blocco non c'entra.
 */
fun classifyNetworkBlock(error: IOException, networkValidated: Boolean, dataSaverRestricting: Boolean): NetworkBlock = when {
    !networkValidated -> NetworkBlock.NONE
    generateSequence<Throwable>(error) { it.cause }.none { it is UnknownHostException || it is ConnectException } -> NetworkBlock.NONE
    dataSaverRestricting -> NetworkBlock.DATA_SAVER
    else -> NetworkBlock.SUSPECTED
}

/** [classifyNetworkBlock] con lo stato della rete letto dal sistema; senza stato leggibile, [NetworkBlock.NONE]. */
fun Context.networkBlockFor(error: IOException): NetworkBlock {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return NetworkBlock.NONE

    // Con la rete bloccata per l'app activeNetwork è null: si guarda allora se il telefono ha una rete funzionante.
    @Suppress("DEPRECATION")
    val networks = manager.activeNetwork?.let(::listOf) ?: manager.allNetworks.toList()
    val validated = networks.mapNotNull(manager::getNetworkCapabilities).any {
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    val dataSaver = manager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    return classifyNetworkBlock(error, validated, dataSaver)
}
