package com.pockettravel.core.sync.testing

import com.pockettravel.core.sync.AppStatus
import com.pockettravel.core.sync.AppStatusClient
import kotlinx.coroutines.CompletableDeferred

/** [AppStatusClient] in memoria: risponde con [status] o lancia [failure]; con [gate] aspetta che il test lo completi. */
class FakeAppStatusClient(var status: AppStatus = AppStatus()) : AppStatusClient {
    var failure: Throwable? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun fetchAppStatus(): AppStatus {
        gate?.await()
        failure?.let { throw it }
        return status
    }
}
