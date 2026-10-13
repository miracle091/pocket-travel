package com.pockettravel.core.sync

/**
 * Catalogo pubblicato da altri con la pipeline del progetto (tools/data-pipeline): indirizzo di manifest.json e
 * chiave pubblica ECDSA P-256 (X.509 SubjectPublicKeyInfo, base64) delle sue firme `.sig`.
 */
data class CustomCatalog(val manifestUrl: String, val publicKey: String)

/** Esito della prova di un catalogo prima di sceglierlo. */
enum class CatalogCheck { OK, INVALID_URL, INVALID_KEY, UNREACHABLE, BAD_SIGNATURE, NOT_A_CATALOG }

/**
 * Catalogo scelto in Impostazioni al posto di quello ufficiale. Si salva solo dopo averlo scaricato e
 * verificato con la sua chiave; vale dal riavvio dell'app (PocketTravelApp lo passa a [SyncConfig] con [applySaved]).
 * app-status.json resta quello del progetto, con la sua chiave.
 */
interface CatalogPreferences {
    /** null = catalogo ufficiale. */
    fun saved(): CustomCatalog?

    /**
     * Passa a [SyncConfig] il catalogo salvato: una volta, all'avvio, prima di WorkManager e degli AppInitializer.
     * Dopo un cambio di catalogo dimentica qui le date di pubblicazione del precedente (non si confrontano con
     * quelle del nuovo): fatto prima, un worker ancora in corso col vecchio catalogo potrebbe riscriverle.
     */
    fun applySaved()

    /** Prova il catalogo e, se e' valido, lo salva. Spazi e a capo copiati con la chiave sono tolti. */
    suspend fun checkAndSave(manifestUrl: String, publicKey: String): CatalogCheck

    /** Torna al catalogo ufficiale. */
    fun reset()
}
