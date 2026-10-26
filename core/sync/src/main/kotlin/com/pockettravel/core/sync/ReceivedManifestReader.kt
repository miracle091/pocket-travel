package com.pockettravel.core.sync

import kotlinx.serialization.json.Json
import javax.inject.Inject

/** Esito della lettura di un `manifest.json` ricevuto da un altro telefono, con [ReceivedManifestReader]. */
sealed interface ReceivedManifest {
    /** Firma valida e catalogo non piu' vecchio di quello gia' accettato: i suoi sha256 valgono per i file ricevuti. */
    data class Valid(val manifest: RegionManifest) : ReceivedManifest

    /**
     * Firma valida, ma catalogo piu' vecchio di quello gia' accettato su questo telefono: non si usa, e dei file ricevuti
     * valgono solo quelli che il catalogo di questo telefono conosce con lo stesso sha256.
     */
    data object Older : ReceivedManifest

    /** Firma sbagliata o contenuto non leggibile: nessun file ricevuto con questo catalogo si installa. */
    data object Invalid : ReceivedManifest
}

/**
 * Catalogo e firma ricevuti da un altro telefono (scambio tra telefoni), letti senza rete: stessa chiave e stesso controllo
 * di `publishedAt` del catalogo scaricato ([ManifestSignatureVerifier.verifyWith]). Un catalogo valido piu' recente
 * aggiorna l'ultimo `publishedAt` accettato, come quello letto dal sito.
 */
class ReceivedManifestReader @Inject constructor(
    private val json: Json,
    private val signatureVerifier: ManifestSignatureVerifier,
) {
    fun read(content: ByteArray, signature: ByteArray): ReceivedManifest = try {
        // Prima del controllo di publishedAt, come in SignedFileCache: un file firmato ma non valido non lo aggiorna.
        val manifest = parseManifest(json, content)
        signatureVerifier.verifyWith(SyncConfig.MANIFEST_URL, content, signature)
        ReceivedManifest.Valid(manifest)
    } catch (ignored: OlderPublishedFileException) {
        ReceivedManifest.Older
    } catch (ignored: ManifestSignatureException) {
        ReceivedManifest.Invalid
    } catch (ignored: IllegalArgumentException) {
        ReceivedManifest.Invalid
    } catch (ignored: IllegalStateException) {
        ReceivedManifest.Invalid
    }
}
