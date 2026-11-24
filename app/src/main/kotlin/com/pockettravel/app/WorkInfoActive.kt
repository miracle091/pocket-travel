package com.pockettravel.app

import androidx.work.WorkInfo

/** Il lavoro e' in coda o in corso (null: nessun lavoro registrato). */
internal fun WorkInfo?.isActive(): Boolean = this?.state == WorkInfo.State.RUNNING || this?.state == WorkInfo.State.ENQUEUED
