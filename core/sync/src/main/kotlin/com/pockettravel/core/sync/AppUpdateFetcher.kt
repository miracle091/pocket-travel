package com.pockettravel.core.sync

import com.pockettravel.core.data.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Scarica l'APK di [AppVersionEntry] in [AppUpdateStorage] (ripresa, tetto di byte e SHA-256 di
 * [RegionPackageDownloader]) e lo controlla con [checkApk]; un APK che non passa si cancella.
 */
class AppUpdateFetcher @Inject constructor(
    private val downloader: RegionPackageDownloader,
    private val inspector: ApkInspector,
    private val storage: AppUpdateStorage,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** null: [AppUpdateStorage.apkFile] è pronto e controllato. [entry] ha l'impronta e la dimensione dell'APK. */
    suspend fun fetch(
        entry: AppVersionEntry,
        url: String = SyncConfig.appApkUrl(entry.versionName),
        onProgress: suspend (bytesDownloaded: Long) -> Unit = {},
    ): AppUpdateFailure? = withContext(ioDispatcher) {
        val sha256 = requireNotNull(entry.apkSha256) { "apkSha256 mancante" }
        val sizeBytes = requireNotNull(entry.apkSizeBytes) { "apkSizeBytes mancante" }
        val target = storage.apkFile(entry.versionCode)
        storage.dir.mkdirs()
        // Un .apk esiste solo dopo la verifica dell'impronta: un secondo avvio riparte dal controllo.
        if (!target.exists()) {
            downloader.downloadAndVerify(RegionManifestFile(target.name, url, sizeBytes, sha256), target, onProgress)
        }
        checkApk(inspector.archive(target), inspector.installed()).also { failure ->
            if (failure != null) storage.delete(entry.versionCode)
        }
    }
}
