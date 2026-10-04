package com.pockettravel.feature.map

import btools.router.OsmTrack
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.RoutingParamCollector
import btools.router.TurnInstructions
import com.pockettravel.core.data.Rd5Merger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

// BRouter funziona su Android ART; il suo sorgente e' vendorizzato in :third-party:brouter-core.
//
// API di btools.router.RoutingEngine/RoutingContext/RoutingParamCollector non documentata per uso
// embedded: ricostruita leggendo il bytecode (javap) di btools.server.BRouter.main(), il comando CLI
// standalone ufficiale.
//
// segmentDir: cartella con uno o piu' file .rd5 (segmenti BRouter per la regione). profileDir:
// cartella con "<profileName>.brf" + lookups.dat — bundlati nell'app (asset, non dati per-regione:
// stesso profilo per tutte le regioni), vedi RouteEngineModule.
class BRouterRouteEngine(
    private val segmentDir: File,
    private val profileDir: File,
    private val profileName: String = "trekking",
    private val maxRunningTimeMillis: Long = 60_000,
) : RouteEngine {

    override suspend fun route(
        from: RoutePoint,
        to: RoutePoint,
        profile: String?,
        profileParams: Map<String, String>,
        onProgress: (Double) -> Unit,
    ): RouteResult {
        // Con la cartella dei segmenti uniti (Rd5Merger) le tile possono stare solo nella cartella secondaria.
        val noSegments = withContext(Dispatchers.IO) {
            segmentDir.listFiles { file -> file.extension == "rd5" || file.name == Rd5Merger.STORAGE_CONFIG_FILE }.isNullOrEmpty()
        }
        if (noSegments) return RouteResult.NoRoutingData
        val running = AtomicReference<RoutingEngine?>()
        return coroutineScope {
            // BRouter non guarda l'interrupt del thread: se la coroutine viene annullata, terminate()
            // ferma il ciclo di calcolo, che controlla quel flag.
            val stopper = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    running.get()?.terminate()
                }
            }
            // Stima dell'avanzamento (RoutingEngine.getProgress, aggiunta di Pocket Travel in brouter-core).
            val progressPoller = launch(Dispatchers.Default) {
                while (true) {
                    running.get()?.let { onProgress(it.progress) }
                    delay(PROGRESS_INTERVAL_MILLIS)
                }
            }
            try {
                // Calcolo bloccante di minuti (anche in auto): su IO, per non occupare i thread di Default.
                runInterruptible(Dispatchers.IO) { compute(from, to, profile ?: profileName, profileParams, running) }
            } finally {
                stopper.cancel()
                progressPoller.cancel()
            }
        }
    }

    private fun compute(
        from: RoutePoint,
        to: RoutePoint,
        profile: String,
        profileParams: Map<String, String>,
        running: AtomicReference<RoutingEngine?>,
    ): RouteResult = BROUTER_RUNTIME_LOCK.withInterruptibleLock {
        // segmentBaseDir/profileBaseDir sono System property globali (stesso meccanismo usato da
        // btools.server.BRouter.main()), non parametri del costruttore — impostate ad ogni
        // chiamata perche' regionId (quindi segmentDir) puo' cambiare tra una route() e l'altra
        // sulla stessa istanza factory-creata.
        System.setProperty("segmentBaseDir", segmentDir.path)
        System.setProperty("profileBaseDir", profileDir.path)

        val lonlats = "${from.longitude},${from.latitude}|${to.longitude},${to.latitude}"
        val routingContext = RoutingContext()
        val paramCollector = RoutingParamCollector()
        val waypoints = paramCollector.getWayPointList(lonlats)
        // timode=1: i profili hanno turnInstructionMode = 1 ("scelta automatica da chi chiede"), che per
        // RoutingContext.readGlobalConfig vuol dire tenere il valore della richiesta; senza timode
        // resterebbe 0 e BRouter non calcolerebbe le svolte.
        // Variabili del profilo come "profile:<nome>=<valore>" (RoutingParamCollector le passa al profilo).
        val extra = profileParams.entries.joinToString("") { (key, value) -> "&profile:$key=$value" }
        val params = paramCollector.getUrlParams("lonlats=$lonlats&profile=$profile&timode=1$extra")
        paramCollector.setParams(routingContext, waypoints, params)

        val engine = RoutingEngine(null, null, segmentDir, waypoints, routingContext)
        running.set(engine)
        // Annullata prima che il motore fosse registrato (anche mentre aspettava il lock):
        // runInterruptible ha gia' interrotto il thread, lo stopper non l'ha visto.
        if (Thread.currentThread().isInterrupted) engine.terminate()
        engine.doRun(maxRunningTimeMillis)

        engine.errorMessage?.let { message ->
            // Messaggi di NodesCache/RoutingEngine: "datafile ... not found",
            // "from-position not mapped in existing datafile".
            // "... timeout after N seconds": tempo massimo (maxRunningTimeMillis) superato.
            return when {
                "not found" in message || "not mapped" in message -> RouteResult.NoRoutingData
                "timeout" in message -> RouteResult.TimedOut
                else -> RouteResult.Failed(message)
            }
        }
        val track = engine.foundTrack ?: return RouteResult.NotFound
        if (track.nodes.isEmpty()) return RouteResult.NotFound
        RouteResult.Found(track.toRoute())
    }

    private companion object {
        // Un calcolo alla volta (System property globali): un ReentrantLock e non synchronized, cosi' chi viene
        // annullato mentre aspetta (l'assistente IA al suo tempo massimo, col Navigatore che calcola) smette subito.
        private val BROUTER_RUNTIME_LOCK = ReentrantLock()
        private const val PROGRESS_INTERVAL_MILLIS = 250L

        // Formato punto fisso di BRouter per lat/lon (verificato in RoutingParamCollector:
        // "ilon = (lon + 180) * 1_000_000", "ilat = (lat + 90) * 1_000_000") — inverso qui.
        fun ilatToLat(ilat: Int) = ilat / 1_000_000.0 - 90.0
        fun ilonToLon(ilon: Int) = ilon / 1_000_000.0 - 180.0

        fun OsmTrack.toRoute(): Route {
            val turns = TurnInstructions.of(this).mapNotNull { turn ->
                turnType(turn.command)?.let { type ->
                    // Uscita negativa per le rotonde percorse a sinistra (VoiceHintProcessor: -roundaboutExit).
                    TurnInstruction(type, turn.distanceToNext, turn.indexInTrack, if (type.isRoundabout) abs(turn.roundaboutExit) else 0)
                }
            }
            // Il post-processing di BRouter toglie l'indicazione di arrivo (END): si rimette
            // sull'ultimo punto, cosi' l'elenco finisce sempre con l'arrivo, anche senza svolte.
            val arrive = TurnInstruction(TurnType.ARRIVE, 0.0, nodes.lastIndex)
            return Route(
                points = nodes.map { RoutePoint(ilatToLat(it.getILat()), ilonToLon(it.getILon())) },
                distanceMeters = distance.toDouble(),
                durationSeconds = getTotalSeconds().toDouble(),
                instructions = if (turns.lastOrNull()?.type == TurnType.ARRIVE) turns else turns + arrive,
            )
        }

        // OFF_ROUTE e BEELINE (tratti in linea d'aria) non sono svolte da mostrare.
        fun turnType(command: Int): TurnType? = when (command) {
            TurnInstructions.CONTINUE -> TurnType.CONTINUE
            TurnInstructions.TURN_SLIGHTLY_LEFT -> TurnType.SLIGHT_LEFT
            TurnInstructions.TURN_LEFT -> TurnType.LEFT
            TurnInstructions.TURN_SHARPLY_LEFT -> TurnType.SHARP_LEFT
            TurnInstructions.TURN_SLIGHTLY_RIGHT -> TurnType.SLIGHT_RIGHT
            TurnInstructions.TURN_RIGHT -> TurnType.RIGHT
            TurnInstructions.TURN_SHARPLY_RIGHT -> TurnType.SHARP_RIGHT
            TurnInstructions.KEEP_LEFT -> TurnType.KEEP_LEFT
            TurnInstructions.KEEP_RIGHT -> TurnType.KEEP_RIGHT
            TurnInstructions.EXIT_LEFT -> TurnType.EXIT_LEFT
            TurnInstructions.EXIT_RIGHT -> TurnType.EXIT_RIGHT
            TurnInstructions.U_TURN, TurnInstructions.U_TURN_LEFT, TurnInstructions.U_TURN_RIGHT -> TurnType.U_TURN
            TurnInstructions.ROUNDABOUT -> TurnType.ROUNDABOUT
            TurnInstructions.ROUNDABOUT_LEFT -> TurnType.ROUNDABOUT_LEFT
            TurnInstructions.END -> TurnType.ARRIVE
            else -> null
        }
    }
}

/** [block] con il lock preso con lockInterruptibly: un thread interrotto mentre aspetta (runInterruptible annullato) esce subito. */
internal inline fun <T> ReentrantLock.withInterruptibleLock(block: () -> T): T {
    lockInterruptibly()
    try {
        return block()
    } finally {
        unlock()
    }
}
