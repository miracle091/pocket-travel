package com.pockettravel.pipeline

import btools.mapcreator.OsmFastCutter
import btools.mapcreator.PosUnifier
import btools.mapcreator.WayLinker
import java.io.File
import kotlin.io.path.createTempDirectory

fun main(args: Array<String>) {
    require(args.size == 2) { "Uso: generateRoutingGraph <input.osm.xml> <output-dir-rd5>" }
    val osmXmlFile = File(args[0])
    val outputDir = File(args[1])

    val rd5Files = generateRd5(osmXmlFile, outputDir)
    val totalBytes = rd5Files.sumOf { it.length() }
    println("${rd5Files.size} file .rd5 generati in $outputDir: $totalBytes byte (${rd5Files.joinToString { it.name }})")
}

/**
 * Converte l'estratto OSM XML in .osm.pbf (formato richiesto da OsmFastCutter — verificato
 * leggendo OsmParser.java upstream: legge blob PBF via org.openstreetmap.osmosis.osmbinary, non
 * XML, nonostante il commento javadoc "reads an *.osm from stdin" sia superato), poi esegue i tre
 * stadi ufficiali della pipeline .rd5 di BRouter (misc/scripts/mapcreation/process_pbf_planet.sh
 * upstream, letto direttamente da GitHub): OsmFastCutter -> PosUnifier ->
 * WayLinker. Stessa struttura di cartelle di lavoro dello script ufficiale, senza gli stadi
 * opzionali (elevazione SRTM, pseudo-tag da database) non necessari per un estratto regionale
 * piccolo.
 *
 * parseOsmXml/writeOsmPbf vengono da :tools:data-pipeline:maptiles (stesso package
 * com.pockettravel.pipeline, nessun import necessario): la separazione storica da :routing era
 * dovuta a un conflitto di versione hppc tra planetiler-core e graphhopper-core (vedi
 * maptiles/build.gradle.kts), non piu' rilevante ora che GraphHopper e' stato rimosso da questo
 * modulo (Fase 11).
 */
fun generateRd5(osmXmlFile: File, outputDir: File): List<File> {
    // Senza questo, OsmParser (letto da btools.mapcreator.OsmParser upstream) tratta il .pbf
    // come uno stream in crescita (pensato per un planet scaricato in diretta con osmupdate) e
    // aspetta 10s a ogni blob finche' non passano 2 minuti dall'ultima variazione di dimensione
    // — inutile per un file finito gia' completo, misurato: senza questa property un estratto
    // minuscolo impiega piu' di 2 minuti solo in attesa.
    System.setProperty("avoidMapPolling", "true")

    val workDir = createTempDirectory("brouter-map-creator").toFile()
    try {
        val pbfFile = File(workDir, "region.osm.pbf")
        writeOsmPbf(parseOsmXml(osmXmlFile), pbfFile)

        val lookupFile = File(brouterProfileDir, "lookups.dat")
        // Un solo profilo per tutti e tre gli slot richiesti da OsmFastCutter (filtro/report/
        // check): l'app instrada solo con "trekking" (feature/map/BRouterRouteEngine.kt), quindi
        // una way che questo profilo scarterebbe qui non servirebbe comunque a runtime. report/
        // check servono solo a classificare relazioni di rete ciclabile, non rilevanti per un
        // profilo pedonale.
        val profileFile = File(brouterProfileDir, "trekking.brf")

        val nodeDir = File(workDir, "nodetiles").apply { mkdirs() }
        val wayDir = File(workDir, "waytiles").apply { mkdirs() }
        val node55Dir = File(workDir, "nodes55").apply { mkdirs() }
        val way55Dir = File(workDir, "waytiles55").apply { mkdirs() }
        val borderNidsFile = File(workDir, "bordernids.dat")
        val relFile = File(workDir, "relations.dat")
        // Accettato dalla firma di OsmFastCutter.doCut/WayLinker.process ma mai letto ne'
        // scritto da nessuno dei due (verificato leggendo entrambi i sorgenti: le restrizioni
        // viaggiano davvero per la cartella "restrictions55" creata automaticamente accanto a
        // nodeDir, non per questo file) — solo un parametro CLI legacy dello script ufficiale.
        val restrictionsFile = File(workDir, "restrictions.dat")

        OsmFastCutter.doCut(
            lookupFile, nodeDir, wayDir, node55Dir, way55Dir, borderNidsFile, relFile, restrictionsFile,
            profileFile, profileFile, profileFile, pbfFile, null,
        )

        val unodes55Dir = File(workDir, "unodes55").apply { mkdirs() }
        val borderNodesFile = File(workDir, "bordernodes.dat")
        // Nessun dato di elevazione SRTM (fuori scope): una cartella inesistente
        // e' sicura, PosUnifier controlla File.exists() prima di leggere (btools.mapcreator.
        // PosUnifier.srtmForNode upstream).
        val noSrtmDir = File(workDir, "no-srtm").path
        PosUnifier().process(node55Dir, unodes55Dir, borderNidsFile, borderNodesFile, noSrtmDir, noSrtmDir)

        outputDir.mkdirs()
        WayLinker().process(unodes55Dir, way55Dir, borderNodesFile, restrictionsFile, lookupFile, profileFile, outputDir, "rd5")

        return outputDir.listFiles { file -> file.extension == "rd5" }?.toList().orEmpty()
    } finally {
        workDir.deleteRecursively()
    }
}

private val brouterProfileDir = File("testdata/brouter-profile")
