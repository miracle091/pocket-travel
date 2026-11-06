package com.pockettravel.core.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/** La cartella `app-update` con gli APK scaricati (`<versionCode>.apk`, durante il download `<versionCode>.apk.part`). */
class AppUpdateStorage(val dir: File) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, DIR_NAME))

    fun apkFile(versionCode: Int) = File(dir, "$versionCode.apk")

    fun partFile(versionCode: Int) = File(dir, "$versionCode.apk.part")

    fun delete(versionCode: Int) {
        apkFile(versionCode).delete()
        partFile(versionCode).delete()
    }

    /** Toglie i file dell'app già installata (anche dopo un aggiornamento fatto dal browser) e ogni file estraneo. */
    fun cleanUp(installedVersionCode: Long) {
        dir.listFiles().orEmpty()
            .filter { isStaleUpdateFile(it.name, installedVersionCode) }
            .forEach { it.deleteRecursively() }
    }

    private companion object {
        const val DIR_NAME = "app-update"
    }
}

private val UPDATE_FILE_NAME = Regex("(\\d+)\\.apk(\\.part)?")

internal fun isStaleUpdateFile(name: String, installedVersionCode: Long): Boolean {
    val versionCode = UPDATE_FILE_NAME.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull() ?: return true
    return versionCode <= installedVersionCode
}
