package com.pockettravel.core.sync.testing

import com.pockettravel.core.sync.CatalogCheck
import com.pockettravel.core.sync.CatalogPreferences
import com.pockettravel.core.sync.CustomCatalog

/** [CatalogPreferences] in memoria: [result] decide l'esito della prova, le prove restano in [checked]. */
class FakeCatalogPreferences(var saved: CustomCatalog? = null) : CatalogPreferences {
    var result: suspend () -> CatalogCheck = { CatalogCheck.OK }
    val checked = mutableListOf<Pair<String, String>>()
    var resets = 0
        private set
    var applied = 0
        private set

    override fun saved() = saved

    override fun applySaved() {
        applied++
    }

    override suspend fun checkAndSave(manifestUrl: String, publicKey: String): CatalogCheck {
        checked += manifestUrl to publicKey
        return result()
    }

    override fun reset() {
        resets++
        saved = null
    }
}
