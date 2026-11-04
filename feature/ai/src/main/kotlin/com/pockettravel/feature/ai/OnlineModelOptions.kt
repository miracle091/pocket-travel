package com.pockettravel.feature.ai

import com.pockettravel.core.sync.OnlineModelEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * I modelli online proposti nella schermata dell'assistente: l'elenco pubblicato ([OnlineModelCatalog]) e, con una
 * chiave salvata, i modelli che il suo account vede davvero ([OnlineModelAvailability]).
 */
internal class OnlineModelOptions(
    private val catalog: OnlineModelCatalog,
    private val availability: OnlineModelAvailability,
    private val settings: AiSettingsStore,
    private val scope: CoroutineScope,
) {
    // Nomi dei modelli che la chiave salvata vede, per servizio; assente = non letti (si mostrano tutti).
    private val available = MutableStateFlow<Map<OnlineProvider, Set<String>>>(emptyMap())

    /** Emette quando arriva l'elenco pubblicato, che sostituisce quello dell'APK, o quello dei modelli visti dalla chiave. */
    val changes: Flow<Unit> = merge(catalog.list.map { }, available.map { })

    /** Il modello che risponde per ogni servizio, con l'impostazione salvata ("" = quello di Automatico). */
    fun modelPerProvider(): Map<OnlineProvider, String> =
        OnlineProvider.entries.associateWith { catalog.modelFor(it, settings.onlineModelSetting(it)) }

    fun options(provider: OnlineProvider): List<OnlineModelEntry> = visibleModels(catalog.models(provider), available.value[provider])

    /** Gli altri modelli di [provider] che la chiave vede in /models; nessuno finche' /models non e' letto. */
    fun others(provider: OnlineProvider): List<OnlineModelEntry> =
        available.value[provider]?.let { names -> catalog.otherModels(provider).filter { isListed(it.id, names) } }.orEmpty()

    fun refresh() {
        scope.launch {
            try {
                catalog.refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Senza rete o con un file non valido resta l'elenco attuale.
            }
        }
    }

    fun checkAvailability() {
        val provider = settings.onlineProvider()
        val apiKey = settings.apiKey()?.takeIf { it.isNotBlank() } ?: return
        val baseUrl = settings.onlineBaseUrl(provider) ?: return
        scope.launch {
            val models = try {
                availability.availableModels(provider, apiKey, baseUrl)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@launch
            }
            // Chiave tolta o cambiata nel frattempo: quei modelli valgono per un altro account.
            if (settings.onlineProvider() != provider || settings.apiKey() != apiKey) return@launch
            available.update { it + (provider to models) }
        }
    }

    /** La chiave di [provider] e' cambiata: i modelli visti con quella di prima non valgono piu'. */
    fun forget(provider: OnlineProvider) = available.update { it - provider }

    fun forgetAll() {
        available.value = emptyMap()
    }
}
