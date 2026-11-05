package com.pockettravel.app.updates

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import com.pockettravel.core.data.IoDispatcher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Esito dell'installazione dell'aggiornamento dell'app: con il successo Android riavvia l'app, quindi non c'è uno stato per il successo. */
enum class AppInstallStatus {
    Idle,

    /** Sessione consegnata al sistema: può chiedere la sua conferma all'utente. */
    Installing,

    /** Sessione non riuscita o annullata dall'utente nella finestra di sistema. */
    Failed,
}

/** Installa l'APK scaricato dell'aggiornamento: dietro un'interfaccia perché il ViewModel si prova con un fake. */
interface AppInstaller {
    val status: StateFlow<AppInstallStatus>

    /** Vero se l'utente ha concesso a Pocket Travel "Installa app sconosciute". */
    fun canInstall(): Boolean

    /** Apre la pagina delle impostazioni di sistema del permesso "Installa app sconosciute" per questa app. */
    fun openInstallSettings()

    /** Avvia la sessione di installazione di [file]; l'esito arriva in [status]. */
    suspend fun install(file: File)
}

/** Esito delle sessioni, scritto da [PackageAppInstaller] e da [AppInstallReceiver]. */
@Singleton
class AppInstallResults @Inject constructor() {
    private val _status = MutableStateFlow(AppInstallStatus.Idle)
    val status: StateFlow<AppInstallStatus> = _status.asStateFlow()

    fun set(status: AppInstallStatus) {
        _status.value = status
    }
}

/** [AppInstaller] su [PackageInstaller]: sessione `MODE_FULL_INSTALL`, esito al [AppInstallReceiver]. */
@Singleton
class PackageAppInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val results: AppInstallResults,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AppInstaller {
    override val status: StateFlow<AppInstallStatus> = results.status

    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun openInstallSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Nessuna app di impostazioni che gestisca la pagina: non c'è dove mandare l'utente.
        }
    }

    override suspend fun install(file: File) {
        results.set(AppInstallStatus.Installing)
        try {
            withContext(ioDispatcher) { commitSession(file) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            results.set(AppInstallStatus.Failed)
        } catch (_: SecurityException) {
            results.set(AppInstallStatus.Failed)
        }
    }

    private fun commitSession(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            // Il tocco su "Installa" è la conferma: il sistema chiede la sua solo se le condizioni non bastano.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite(APK_NAME, 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                session.commit(resultIntent().intentSender)
            }
        } catch (error: IOException) {
            abandon(installer, sessionId)
            throw error
        } catch (error: SecurityException) {
            abandon(installer, sessionId)
            throw error
        }
    }

    // Il sistema aggiunge gli extra dell'esito all'intent: da Android 12 serve FLAG_MUTABLE.
    private fun resultIntent(): PendingIntent {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, 0, Intent(context, AppInstallReceiver::class.java), flags)
    }

    private fun abandon(installer: PackageInstaller, sessionId: Int) {
        try {
            installer.abandonSession(sessionId)
        } catch (_: SecurityException) {
            // Sessione già consegnata o tolta dal sistema: non c'è altro da liberare.
        }
    }

    private companion object {
        const val APK_NAME = "base.apk"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppInstallerModule {
    @Binds
    abstract fun appInstaller(installer: PackageAppInstaller): AppInstaller
}
