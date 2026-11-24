package com.pockettravel.core.sync

import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import com.pockettravel.core.data.runCatchingCancellable

/**
 * Passa il lavoro in primo piano con [info]; false (e un log con [message]) se il sistema non lo permette. Da Android 12 il
 * servizio in primo piano non parte da sfondo (ForegroundServiceStartNotAllowedException): si lavora comunque, senza notifica.
 */
internal suspend fun CoroutineWorker.tryForeground(info: ForegroundInfo, tag: String, message: String): Boolean =
    runCatchingCancellable { setForeground(info) }
        .onFailure { Log.w(tag, message, it) }
        .isSuccess
