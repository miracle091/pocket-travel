package com.pockettravel.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class RegionPackageDownloadOutcomeTest {
    private val firstAttempt = 0
    private val exhausted = 100

    @Test
    fun `il Risparmio dati ferma subito, senza ritentare`() {
        assertEquals(DownloadOutcome.DATA_SAVER_BLOCKED, RegionPackageDownload.outcomeForNetworkError(NetworkBlock.DATA_SAVER, firstAttempt))
    }

    @Test
    fun `un sospetto di blocco si ritenta e si dichiara solo a tentativi finiti`() {
        assertEquals(DownloadOutcome.RETRY, RegionPackageDownload.outcomeForNetworkError(NetworkBlock.SUSPECTED, firstAttempt))
        assertEquals(DownloadOutcome.NETWORK_BLOCKED, RegionPackageDownload.outcomeForNetworkError(NetworkBlock.SUSPECTED, exhausted))
    }

    @Test
    fun `gli altri errori di rete si ritentano e poi falliscono come prima`() {
        assertEquals(DownloadOutcome.RETRY, RegionPackageDownload.outcomeForNetworkError(NetworkBlock.NONE, firstAttempt))
        assertEquals(DownloadOutcome.FAILURE, RegionPackageDownload.outcomeForNetworkError(NetworkBlock.NONE, exhausted))
    }
}
