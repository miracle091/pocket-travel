package com.pockettravel.feature.ai

import android.content.Context
import com.pockettravel.core.data.IoDispatcher
import com.pockettravel.core.sync.ONLINE_MODEL_ROLE_AUTO
import com.pockettravel.core.sync.OnlineModelEntry
import com.pockettravel.core.sync.OnlineModelList
import com.pockettravel.core.sync.OnlineModelsClient
import com.pockettravel.core.sync.parseOnlineModels
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Modelli proposti per ogni servizio online ([OnlineModelList]). All'avvio vale l'ultimo elenco scaricato
 * (salvato in [cacheFile] dopo la verifica della firma) o, se non ce n'e' uno valido, quello incluso nell'APK
 * ([bundled]). [refresh] scarica l'elenco pubblicato: senza rete resta quello che c'era.
 * Un servizio assente dall'elenco scaricato prende i modelli dell'elenco incluso nell'APK.
 */
@Singleton
class OnlineModelCatalog(
    private val bundled: OnlineModelList,
    private val cacheFile: File,
    private val client: OnlineModelsClient,
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        client: OnlineModelsClient,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        bundled = context.assets.open(ASSET_NAME).use { parseOnlineModels(it.readBytes()) },
        cacheFile = File(context.filesDir, ASSET_NAME),
        client = client,
        ioDispatcher = ioDispatcher,
    )

    private val _list = MutableStateFlow(cached() ?: bundled)
    val list: StateFlow<OnlineModelList> = _list.asStateFlow()

    /** Modelli di [provider], prima quello di Automatico. */
    fun models(provider: OnlineProvider): List<OnlineModelEntry> =
        (_list.value.providers[provider.name] ?: bundled.providers[provider.name].orEmpty())
            .sortedByDescending { it.role == ONLINE_MODEL_ROLE_AUTO }

    /** Modello dell'opzione Automatico per [provider]. */
    fun autoModel(provider: OnlineProvider): String =
        models(provider).firstOrNull { it.role == ONLINE_MODEL_ROLE_AUTO }?.id
            ?: error("Nessun modello automatico per $provider")

    /** Modello da usare per [provider] con la scelta salvata [setting]: "" = Automatico, altrimenti il nome. */
    fun modelFor(provider: OnlineProvider, setting: String): String = setting.trim().ifEmpty { autoModel(provider) }

    /** Se mandare la temperatura a [model]: dall'elenco se ci e' (anche come scelta passata), altrimenti dal nome. */
    fun sendsTemperature(provider: OnlineProvider, model: String): Boolean =
        models(provider).firstOrNull { it.id == model }?.temperature ?: supportsTemperature(model)

    /** Scarica e salva l'elenco pubblicato; un errore (rete, firma, contenuto) lascia l'elenco attuale. */
    suspend fun refresh() {
        val bytes = client.fetchOnlineModels()
        val downloaded = parseOnlineModels(bytes)
        withContext(ioDispatcher) {
            val temp = File(cacheFile.path + ".tmp")
            temp.writeBytes(bytes)
            Files.move(temp.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        _list.value = downloaded
    }

    // Un file salvato illeggibile (non succede: si salva solo dopo verifica e lettura) vale come assente.
    private fun cached(): OnlineModelList? =
        runCatching { parseOnlineModels(cacheFile.readBytes()) }.getOrNull()

    private companion object {
        const val ASSET_NAME = "online-models.json"
    }
}

/**
 * Costo stimato in dollari di 100 domande, mostrato accanto al modello: 2.500 token in ingresso (domanda, istruzioni
 * e contesto delle guide) e 300 in uscita (una risposta di poche frasi) per domanda.
 */
internal fun OnlineModelEntry.usdPer100Questions(): Double =
    QUESTIONS * (INPUT_TOKENS * inputUsdPerMTok + OUTPUT_TOKENS * outputUsdPerMTok) / TOKENS_PER_PRICE

private const val QUESTIONS = 100
private const val INPUT_TOKENS = 2_500
private const val OUTPUT_TOKENS = 300
private const val TOKENS_PER_PRICE = 1_000_000.0
