package com.pockettravel.core.sync

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

/** Perché un aggiornamento dell'app non è pronto: download o controllo dell'APK scaricato. */
enum class AppUpdateFailure {
    /** Rete: i tentativi sono finiti. */
    NETWORK,

    /** Dimensione o SHA-256 diversi da app-status.json, o risposta del server rifiutata. */
    INVALID_FILE,

    /** Il file non è un APK leggibile. */
    UNREADABLE,

    /** L'APK è di un altro pacchetto. */
    PACKAGE,

    /** L'APK non ha un `versionCode` più alto di quello installato. */
    NOT_NEWER,

    /** L'APK è firmato con un altro certificato (build di debug, fork): l'installazione fallirebbe. */
    SIGNATURE,
}

/** Identità di un APK o dell'app installata; [signers] sono gli SHA-256 dei certificati di firma. */
data class ApkIdentity(val packageName: String, val versionCode: Long, val signers: Set<String>)

/** Il controllo prima dell'installazione; null se l'APK può sostituire l'app installata. */
fun checkApk(archive: ApkIdentity?, installed: ApkIdentity): AppUpdateFailure? = when {
    archive == null -> AppUpdateFailure.UNREADABLE
    archive.packageName != installed.packageName -> AppUpdateFailure.PACKAGE
    archive.versionCode <= installed.versionCode -> AppUpdateFailure.NOT_NEWER
    archive.signers.isEmpty() || archive.signers != installed.signers -> AppUpdateFailure.SIGNATURE
    else -> null
}

/** Identità dell'app installata e di un APK. */
interface ApkInspector {
    fun installed(): ApkIdentity
    fun archive(file: File): ApkIdentity?
}

/** [ApkInspector] sul PackageManager. */
class PackageManagerApkInspector @Inject constructor(@ApplicationContext private val context: Context) : ApkInspector {
    override fun installed(): ApkIdentity = identity(context.packageManager.getPackageInfo(context.packageName, flags()))

    override fun archive(file: File): ApkIdentity? =
        context.packageManager.getPackageArchiveInfo(file.path, flags())?.let(::identity)

    @Suppress("DEPRECATION")
    private fun flags() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): ApkIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        return ApkIdentity(
            info.packageName,
            code,
            signatures.orEmpty().mapTo(mutableSetOf()) { sha256Hex(it.toByteArray()) },
        )
    }

    private fun sha256Hex(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }

    private companion object {
        const val BYTE_MASK = 0xFF
    }
}
