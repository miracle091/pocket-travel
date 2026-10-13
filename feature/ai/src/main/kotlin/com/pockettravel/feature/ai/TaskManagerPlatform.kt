package com.pockettravel.feature.ai

import android.content.Context
import android.content.Intent
import android.os.Process
import com.pockettravel.core.data.PreferencesFactory
import com.pockettravel.feature.ai.llamacpp.CpuBackendOverride
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Scelta a mano della variante CPU di llama.cpp, per il task manager di debug (vedi [CpuBackendOverride]). */
interface CpuBackendStore {
    /** Nomi dei file delle varianti CPU nell'APK installato. */
    fun available(): List<String>

    /** Variante scelta per il prossimo avvio; null se la sceglie ggml. */
    fun saved(): String?

    fun save(fileName: String?)
}

/** Riavvio del processo dell'app. */
fun interface AppRestarter {
    fun restart()
}

class AndroidCpuBackendStore @Inject constructor(
    @ApplicationContext private val context: Context,
    preferencesFactory: PreferencesFactory,
) : CpuBackendStore {
    private val prefs = preferencesFactory.open(CpuBackendOverride.PREFS)

    override fun available(): List<String> = CpuBackendOverride.available(context)
    override fun saved(): String? = CpuBackendOverride.saved(prefs, CpuBackendOverride.isDebuggable(context))
    override fun save(fileName: String?) = CpuBackendOverride.save(prefs, fileName)
}

/**
 * killProcess e non Runtime.exit, che eseguirebbe i distruttori delle librerie native mentre il motore puo' ancora
 * generare.
 */
class AndroidAppRestarter @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppRestarter {
    override fun restart() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        context.startActivity(Intent.makeRestartActivityTask(launch.component))
        Process.killProcess(Process.myPid())
    }
}
