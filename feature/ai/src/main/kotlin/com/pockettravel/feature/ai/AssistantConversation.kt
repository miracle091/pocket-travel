package com.pockettravel.feature.ai

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import com.pockettravel.core.data.ConversationHandle
import com.pockettravel.core.data.ConversationRepository
import com.pockettravel.core.data.ConversationSummary
import com.pockettravel.core.data.ConversationTurn
import com.pockettravel.core.data.RegionRepository
import com.pockettravel.core.data.ResumedConversation
import com.pockettravel.core.data.crypto.HistoryReader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Se la cronologia si puo' usare: le sue chiavi richiedono una biometria forte configurata sul dispositivo. */
interface HistoryAvailability {
    fun isAvailable(): Boolean
}

class BiometricHistoryAvailability @Inject constructor(@ApplicationContext private val context: Context) : HistoryAvailability {
    override fun isAvailable(): Boolean = BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
}


/**
 * Lo stato della conversazione, come lo stato di un cco-task: piccolo, strutturato e ricavato dai turni senza il modello.
 * Alla ripresa si ricostruisce dai turni salvati e rimette la base dei seguiti ([lastQuestion]) e la regione.
 */
data class ConversationState(
    val regionId: String,
    val cities: List<String> = emptyList(),
    val destination: String? = null,
    val lastQuestion: String? = null,
) {
    fun after(turn: ConversationTurn) =
        copy(cities = (cities + turn.cities).distinct(), destination = turn.destination ?: destination, lastQuestion = turn.question)

    companion object {
        fun of(regionId: String, turns: List<ConversationTurn>) = turns.fold(ConversationState(regionId), ConversationState::after)
    }
}

/** La conversazione aperta come la mostra il task manager dei modelli (solo build di debug). */
data class ConversationDebugInfo(
    val mode: AiEngineMode,
    val saved: Boolean,
    val startedAtMs: Long,
    val lastTurnAtMs: Long,
    val turns: Int,
    val archiveCount: Int,
    val resumeCount: Int,
    val lastResumedAtMs: Long?,
    val state: ConversationState,
)

/** La conversazione aperta per il task manager; null quando nessuna schermata dell'assistente ne ha una. Solo in memoria. */
@Singleton
class ConversationMonitor @Inject constructor() {
    val current = MutableStateFlow<ConversationDebugInfo?>(null)
}

/**
 * La conversazione aperta nella schermata dell'assistente: lo stato (base dei seguiti, regione, citta', destinazione) e,
 * con il motore Sul dispositivo, il salvataggio nella cronologia. Con il motore Online resta tutto in memoria: nessun
 * turno va nel database, nemmeno quando la stessa conversazione era cominciata sul dispositivo.
 */
class AssistantConversation @Inject constructor(
    private val repository: ConversationRepository,
    private val regionRepository: RegionRepository,
    private val historyAvailability: HistoryAvailability,
    private val monitor: ConversationMonitor,
) {
    private var handle: ConversationHandle? = null
    private var debug: ConversationDebugInfo? = null

    /** La conversazione salvata aperta; null finche' non ha un turno salvato. */
    val id: String? get() = handle?.id

    var state: ConversationState? = null
        private set

    /** L'ultima domanda con risposta (con quella prima se era un seguito): la base di un seguito ("E a Lisbona?"). */
    val previousQuestion: String? get() = state?.lastQuestion

    /** Regione di una conversazione ripresa: le domande dopo la usano al posto di quella della schermata. */
    var regionId: String? = null
        private set

    /** Regione (nome) di una conversazione ripresa in sola lettura, perche' non e' piu' installata; null altrimenti. */
    var readOnlyRegionName: String? = null
        private set

    /** Un turno con risposta: aggiorna lo stato e, sul dispositivo, si salva. */
    fun onAnswered(mode: AiEngineMode, regionId: String, modelId: String, askedQuestion: String, answer: AssistantAnswer) {
        val now = System.currentTimeMillis()
        val turn = ConversationTurn(
            askedQuestion = askedQuestion,
            question = answer.question ?: askedQuestion,
            answer = answer.text,
            citations = answer.sourceCitations,
            showOfficialSourceBanner = answer.showOfficialSourceBanner,
            officialSourceUrl = answer.officialSourceUrl,
            modelId = modelId,
            cities = answer.cities,
            destination = answer.destination,
            askedAt = now,
        )
        state = (state ?: ConversationState(regionId)).after(turn)
        val saves = when (mode) {
            AiEngineMode.ONLINE -> false
            AiEngineMode.ON_DEVICE -> historyAvailability.isAvailable()
        }
        if (saves) {
            val current = handle
            if (current != null) {
                repository.append(current, turn)
            } else {
                // Il nome della regione si legge nella coda delle scritture: qui non si sospende, cosi' la fine della
                // conversazione (cancelAsk) non puo' cadere tra il turno e la sua conversazione.
                handle = repository.start(
                    { ConversationSummary(askedQuestion, regionId, regionRepository.installed(regionId)?.displayName ?: regionId) },
                    turn,
                )
            }
        }
        publish(
            (debug ?: ConversationDebugInfo(mode, saves, now, now, 0, 0, 0, null, checkNotNull(state))).let {
                it.copy(mode = mode, saved = handle != null, lastTurnAtMs = now, turns = it.turns + 1, state = checkNotNull(state))
            },
        )
    }

    /** Domanda senza risposta: un seguito non si riferisce a una risposta che non si vede. */
    fun onFailed() {
        state = state?.copy(lastQuestion = null)
    }

    /** La conversazione lascia la schermata: si archivia, e la prossima domanda ne comincia una nuova. */
    fun end() {
        handle?.let(repository::archive)
        handle = null
        state = null
        regionId = null
        readOnlyRegionName = null
        // Solo chi l'ha pubblicata la toglie: anche le Impostazioni hanno un ViewModel dell'assistente, senza conversazione.
        if (debug != null) publish(null)
    }

    /**
     * Riprende una conversazione della cronologia al posto di quella aperta; null se non c'e' piu'. Se la sua regione non
     * e' piu' installata si apre in sola lettura ([readOnlyRegionName]): senza le guide della regione le risposte nuove non
     * avrebbero il contesto dei turni salvati.
     */
    suspend fun resume(reader: HistoryReader, id: String): ResumedConversation? {
        val resumed = repository.resume(reader, id) ?: return null
        val installed = regionRepository.installed(resumed.summary.regionId) != null
        end()
        if (installed) continueWith(resumed) else readOnlyRegionName = resumed.summary.regionName
        return resumed
    }

    private fun continueWith(resumed: ResumedConversation) {
        handle = resumed.handle
        state = ConversationState.of(resumed.summary.regionId, resumed.turns)
        regionId = resumed.summary.regionId
        publish(
            ConversationDebugInfo(
                mode = AiEngineMode.ON_DEVICE,
                saved = true,
                startedAtMs = resumed.createdAt,
                lastTurnAtMs = resumed.turns.lastOrNull()?.askedAt ?: resumed.createdAt,
                turns = resumed.turns.size,
                archiveCount = resumed.archiveCount,
                resumeCount = resumed.resumeCount,
                lastResumedAtMs = System.currentTimeMillis(),
                state = checkNotNull(state),
            ),
        )
    }

    private fun publish(info: ConversationDebugInfo?) {
        debug = info
        monitor.current.value = info
    }
}
