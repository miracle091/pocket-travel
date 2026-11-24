package com.pockettravel.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class NetworkBlockTest {

    @Test
    fun `host non risolto con rete valida e Risparmio dati attivo e' Risparmio dati`() {
        assertEquals(NetworkBlock.DATA_SAVER, classifyNetworkBlock(UnknownHostException(), networkValidated = true, dataSaverRestricting = true))
    }

    @Test
    fun `connessione rifiutata con rete valida e Risparmio dati attivo e' Risparmio dati`() {
        assertEquals(NetworkBlock.DATA_SAVER, classifyNetworkBlock(ConnectException(), networkValidated = true, dataSaverRestricting = true))
    }

    @Test
    fun `errore immediato con rete valida e senza Risparmio dati e' sospetto`() {
        assertEquals(NetworkBlock.SUSPECTED, classifyNetworkBlock(UnknownHostException(), networkValidated = true, dataSaverRestricting = false))
        assertEquals(NetworkBlock.SUSPECTED, classifyNetworkBlock(ConnectException(), networkValidated = true, dataSaverRestricting = false))
    }

    @Test
    fun `l'errore immediato vale anche come causa di un altro errore`() {
        val wrapped = IOException("Download di un segmento non riuscito", ConnectException())
        assertEquals(NetworkBlock.SUSPECTED, classifyNetworkBlock(wrapped, networkValidated = true, dataSaverRestricting = false))
    }

    @Test
    fun `senza rete valida non c'e' blocco`() {
        assertEquals(NetworkBlock.NONE, classifyNetworkBlock(UnknownHostException(), networkValidated = false, dataSaverRestricting = true))
        assertEquals(NetworkBlock.NONE, classifyNetworkBlock(ConnectException(), networkValidated = false, dataSaverRestricting = false))
    }

    @Test
    fun `timeout e altri errori di I O non sono un blocco`() {
        assertEquals(NetworkBlock.NONE, classifyNetworkBlock(SocketTimeoutException(), networkValidated = true, dataSaverRestricting = true))
        assertEquals(NetworkBlock.NONE, classifyNetworkBlock(IOException("HTTP 503"), networkValidated = true, dataSaverRestricting = false))
    }
}
