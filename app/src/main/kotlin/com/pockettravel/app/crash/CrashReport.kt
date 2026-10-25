package com.pockettravel.app.crash

import com.pockettravel.feature.ai.RamTier
import org.json.JSONException
import org.json.JSONObject

/** Come si è chiusa l'app. */
enum class CrashKind {
    /** Eccezione Java o Kotlin non catturata. */
    JVM,

    /** Segnale nativo (per esempio un `abort()` di llama.cpp). */
    NATIVE,

    /** Applicazione che non risponde. */
    ANR,

    /** Processo chiuso dal sistema per mancanza di memoria. */
    LOW_MEMORY,

    /** Uscita volontaria mentre l'app era in primo piano. */
    EXIT_SELF,
}

/**
 * Rapporto di una chiusura inattesa. Contiene solo dati tecnici: versioni, dispositivo, memoria, modello caricato
 * (id e fasce di RAM), nome della schermata e traccia dell'errore. Mai chat, note, documenti, posizione né chiavi API.
 */
data class CrashReport(
    val kind: CrashKind,
    val occurredAt: Long,
    val appVersion: String,
    val androidVersion: String,
    val device: String,
    val totalRamMb: Long,
    val loadedModelId: String? = null,
    val modelRamTier: RamTier? = null,
    val deviceRamTier: RamTier? = null,
    val screen: String? = null,
    val details: String = "",
) {
    fun toJson(): String = JSONObject()
        .put(KIND, kind.name)
        .put(OCCURRED_AT, occurredAt)
        .put(APP_VERSION, appVersion)
        .put(ANDROID_VERSION, androidVersion)
        .put(DEVICE, device)
        .put(TOTAL_RAM_MB, totalRamMb)
        .put(MODEL_ID, loadedModelId)
        .put(MODEL_TIER, modelRamTier?.name)
        .put(DEVICE_TIER, deviceRamTier?.name)
        .put(SCREEN, screen)
        .put(DETAILS, details)
        .toString()

    companion object {
        private const val KIND = "kind"
        private const val OCCURRED_AT = "occurredAt"
        private const val APP_VERSION = "appVersion"
        private const val ANDROID_VERSION = "androidVersion"
        private const val DEVICE = "device"
        private const val TOTAL_RAM_MB = "totalRamMb"
        private const val MODEL_ID = "modelId"
        private const val MODEL_TIER = "modelTier"
        private const val DEVICE_TIER = "deviceTier"
        private const val SCREEN = "screen"
        private const val DETAILS = "details"

        /** Null se il testo non è un rapporto valido (file troncato da una chiusura a metà scrittura). */
        fun fromJson(text: String): CrashReport? = try {
            val json = JSONObject(text)
            CrashReport(
                kind = CrashKind.valueOf(json.getString(KIND)),
                occurredAt = json.getLong(OCCURRED_AT),
                appVersion = json.getString(APP_VERSION),
                androidVersion = json.getString(ANDROID_VERSION),
                device = json.getString(DEVICE),
                totalRamMb = json.getLong(TOTAL_RAM_MB),
                loadedModelId = json.optStringOrNull(MODEL_ID),
                modelRamTier = json.optStringOrNull(MODEL_TIER)?.let(RamTier::valueOf),
                deviceRamTier = json.optStringOrNull(DEVICE_TIER)?.let(RamTier::valueOf),
                screen = json.optStringOrNull(SCREEN),
                details = json.optString(DETAILS, ""),
            )
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else getString(name)
    }
}
