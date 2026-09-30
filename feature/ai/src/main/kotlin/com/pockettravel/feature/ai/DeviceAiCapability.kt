package com.pockettravel.feature.ai

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Fasce di RAM: ciascuna sblocca un sottoinsieme diverso di LlmModelCatalog.ALL (via
 * LlmModelDefinition.minRamTier) — MINIMO i modelli piu' leggeri, AMPIA quelli piu' pesanti.
 */
enum class RamTier { INSUFFICIENTE, MINIMO, CONFORTEVOLE, AMPIA }

/** Vincolo tecnico: almeno 4 GB di RAM per abilitare l'AI locale, altrimenti va disattivata. */
class DeviceAiCapability @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun isOnDeviceAiSupported(): Boolean = ramTier() != RamTier.INSUFFICIENTE

    fun ramTier(): RamTier {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return RamTier.INSUFFICIENTE
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return ramTierFor(memoryInfo.totalMem)
    }

    /**
     * Numero di thread nativi da usare per l'inferenza llama.cpp: i core disponibili meno 1 (per
     * lasciare margine a UI/sistema), limitato in base alla fascia di RAM cosi' un device con
     * molti core ma poca RAM non viene comunque sovraccaricato.
     */
    fun inferenceThreadCount(): Int = inferenceThreadCountFor(ramTier(), Runtime.getRuntime().availableProcessors())

    companion object {
        const val MIN_RAM_BYTES = 4L * 1024 * 1024 * 1024
        const val COMFORTABLE_RAM_BYTES = 8L * 1024 * 1024 * 1024
        const val AMPLE_RAM_BYTES = 12L * 1024 * 1024 * 1024

        // Estratte a parte per essere testabili in JVM puro: ActivityManager.getMemoryInfo
        // richiede un Context Android, non disponibile nei unit test di questo modulo (solo
        // JUnit, nessun Robolectric — coerente con il resto del modulo, es. buildFtsQuery).
        fun ramTierFor(totalMemBytes: Long): RamTier = when {
            totalMemBytes >= AMPLE_RAM_BYTES -> RamTier.AMPIA
            totalMemBytes >= COMFORTABLE_RAM_BYTES -> RamTier.CONFORTEVOLE
            totalMemBytes >= MIN_RAM_BYTES -> RamTier.MINIMO
            else -> RamTier.INSUFFICIENTE
        }

        // Estratta a parte per essere testabile in JVM puro, stesso motivo di ramTierFor.
        fun inferenceThreadCountFor(ramTier: RamTier, availableCores: Int): Int {
            // La RAM non dice quanti core ha il telefono (molti da 4 GB ne hanno 8): sotto i 12 GB
            // resta il tetto di 4 di prima, solo la fascia AMPIA (telefoni di punta) sale a 6.
            val cap = when (ramTier) {
                RamTier.AMPIA -> 6
                RamTier.CONFORTEVOLE, RamTier.MINIMO, RamTier.INSUFFICIENTE -> 4
            }
            return (availableCores - 1).coerceIn(1, cap)
        }
    }
}
