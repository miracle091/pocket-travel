package com.pockettravel.core.sync

import androidx.core.content.edit
import com.pockettravel.core.data.testing.InMemoryPreferencesFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class RegionManifestCheckNowTest {

    private val day = TimeUnit.DAYS.toMillis(1)
    private val files = InMemoryPreferencesFactory()
    private var enqueued = 0

    private fun checkNow() = RegionManifestCheckNow(files) { enqueued++ }

    @Test
    fun `il primo controllo parte`() {
        checkNow().enqueue(now = day)

        assertEquals(1, enqueued)
    }

    @Test
    fun `un secondo controllo nello stesso giorno non parte`() {
        val checkNow = checkNow()
        checkNow.enqueue(now = day)
        checkNow.enqueue(now = day + day - 1)

        assertEquals(1, enqueued)
    }

    @Test
    fun `dopo un giorno riparte`() {
        val checkNow = checkNow()
        checkNow.enqueue(now = day)
        checkNow.enqueue(now = 2 * day)

        assertEquals(2, enqueued)
    }

    @Test
    fun `il limite di un giorno vale anche dopo il riavvio dell'app`() {
        checkNow().enqueue(now = day)
        checkNow().enqueue(now = day + 1)

        assertEquals(1, enqueued)
    }

    @Test
    fun `legge il file region_manifest_check_now salvato dall'app`() {
        files.open("region_manifest_check_now").edit { putLong("last_enqueued_at", 5 * day) }

        checkNow().enqueue(now = 5 * day + 1)

        assertEquals(0, enqueued)
    }
}
