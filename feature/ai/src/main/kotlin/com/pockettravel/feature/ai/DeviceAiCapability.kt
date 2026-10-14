package com.pockettravel.feature.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Fasce di RAM: ciascuna sblocca un sottoinsieme diverso di LlmModelCatalog.ALL (via
 * LlmModelDefinition.minRamTier) — MINIMO i modelli piu' leggeri, AMPIA quelli piu' pesanti.
 */
enum class RamTier { INSUFFICIENTE, MINIMO, CONFORTEVOLE, AMPIA }

/**
 * Vincolo tecnico: l'IA sul dispositivo c'e' solo se il catalogo dei modelli ha un modello per la fascia di RAM del
 * dispositivo (oggi da 6 GB, il 2B), altrimenti va disattivata.
 */
class DeviceAiCapability @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun isOnDeviceAiSupported(): Boolean = isOnDeviceAiSupportedIn(ramTier())

    fun ramTier(): RamTier {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return RamTier.INSUFFICIENTE
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        // advertisedMem (Android 14+) e' la RAM dichiarata dal produttore; totalMem quella che vede il kernel,
        // senza la memoria riservata (modem, TEE, DMA): su un telefono da 8 GB circa 7,3-7,6 GiB.
        val advertised = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) memoryInfo.advertisedMem else 0L
        return ramTierFor(if (advertised > 0) advertised else memoryInfo.totalMem)
    }

    /**
     * Numero di thread nativi da usare per l'inferenza llama.cpp: i core disponibili meno 1 (per
     * lasciare margine a UI/sistema), limitato in base alla fascia di RAM cosi' un device con
     * molti core ma poca RAM non viene comunque sovraccaricato.
     */
    fun inferenceThreadCount(): Int = inferenceThreadCountFor(ramTier(), Runtime.getRuntime().availableProcessors())

    companion object {
        // Soglie all'85% di 4, 6 e 12 GiB: un telefono da N GB riporta in totalMem meno di N GiB (memoria riservata,
        // GB decimali) ma non sotto l'85%, e un telefono della taglia sotto non ci arriva. Le fasce vengono dalla
        // memoria misurata con llama.cpp (contesto 4096): 0.8B ~0,8 GiB, 2B ~1,9 GiB, 4B ~4,6 GiB, piu' ~0,4 GB
        // dell'app: modello e app occupano circa il 40% della RAM della fascia o meno, il resto e' per sistema e altre app.
        const val MIN_RAM_BYTES = 4L * 1024 * 1024 * 1024 * 85 / 100
        const val COMFORTABLE_RAM_BYTES = 6L * 1024 * 1024 * 1024 * 85 / 100
        const val AMPLE_RAM_BYTES = 12L * 1024 * 1024 * 1024 * 85 / 100

        // Estratte a parte per essere testabili in JVM puro: ActivityManager.getMemoryInfo
        // richiede un Context Android, non disponibile nei unit test di questo modulo (solo
        // JUnit, nessun Robolectric — coerente con il resto del modulo, es. buildFtsQuery).
        fun ramTierFor(memBytes: Long): RamTier = when {
            memBytes >= AMPLE_RAM_BYTES -> RamTier.AMPIA
            memBytes >= COMFORTABLE_RAM_BYTES -> RamTier.CONFORTEVOLE
            memBytes >= MIN_RAM_BYTES -> RamTier.MINIMO
            else -> RamTier.INSUFFICIENTE
        }

        /** True se almeno un modello del catalogo regge la fascia [tier] (mai sotto i 4 GB). */
        fun isOnDeviceAiSupportedIn(tier: RamTier): Boolean =
            tier != RamTier.INSUFFICIENTE && LlmModelCatalog.ALL.any { it.minRamTier <= tier }

        // Estratta a parte per essere testabile in JVM puro, stesso motivo di ramTierFor.
        fun inferenceThreadCountFor(ramTier: RamTier, availableCores: Int): Int {
            // La RAM non dice quanti core ha il telefono (molti da 4 GB ne hanno 8): sotto i 12 GB
            // il tetto resta 4, solo la fascia AMPIA (telefoni di punta) sale a 6.
            val cap = when (ramTier) {
                RamTier.AMPIA -> 6
                RamTier.CONFORTEVOLE, RamTier.MINIMO, RamTier.INSUFFICIENTE -> 4
            }
            return (availableCores - 1).coerceIn(1, cap)
        }
    }
}
