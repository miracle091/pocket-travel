package com.pockettravel.core.sync.testing

import com.pockettravel.core.sync.AppUpdateDownloads
import com.pockettravel.core.sync.AppUpdateState
import com.pockettravel.core.sync.AppVersionEntry
import kotlinx.coroutines.flow.MutableStateFlow

/** [AppUpdateDownloads] in memoria: il test imposta [state] e legge le richieste in [started]. */
class FakeAppUpdateDownloads(var metered: Boolean = false) : AppUpdateDownloads {
    override val state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val started = mutableListOf<Pair<AppVersionEntry, Boolean>>()
    var cancelled = 0

    override fun isNetworkMetered() = metered

    override suspend fun start(entry: AppVersionEntry, allowMetered: Boolean): Boolean {
        if (entry.apkSha256 == null) return false
        started += entry to allowMetered
        return true
    }

    override fun cancel() {
        cancelled++
    }
}
