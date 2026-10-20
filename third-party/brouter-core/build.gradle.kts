// Sorgente di BRouter (https://github.com/abrensch/brouter, MIT License — vedi LICENSE-BROUTER.txt
// in questa cartella) compilato da qui invece che come dipendenza Maven: org.btools:brouter-core
// e' pubblicato solo su GitHub Packages (richiede autenticazione anche in sola lettura) e JitPack
// non riesce a buildare il repo (verificato: il suo ambiente di default usa JDK 8, il Gradle
// wrapper di BRouter richiede 17+ — vedi README Fase 10).
//
// BRouter e' il submodule git third-party/brouter, sul commit di una release upstream e mai modificato.
// Si compilano solo i moduli lato "router" (brouter-core/mapaccess/util/expressions/codec) usati per
// CARICARE un .rd5 e calcolare un percorso — non brouter-mapcreator, che serve solo a GENERARE i .rd5
// e porta con se' osmosis-osm-binary/protobuf (verificato leggendo brouter-core/build.gradle upstream:
// nessuna di queste 5 dipendenze le richiede). Il profilo car-vario.brf carica per nome
// KinematicModel ("---model:btools.router.KinematicModel", Class.forName), che sta in brouter-core.
//
// Le modifiche del progetto ai file upstream stanno come patch in third-party/patches/brouter (percorsi
// dalla root del submodule): il task brouterSources copia i cinque moduli in build/generated/brouter e
// ci applica le patch, e un build con una patch che non si applica fallisce con il suo nome.
// src/main/java ha solo il file del progetto btools/router/TurnInstructions.java (accesso in sola
// lettura alle indicazioni di svolta, i cui campi in VoiceHint sono package-private).
import javax.inject.Inject

plugins {
    id("java-library")
}

java {
    // Stesso target del build ufficiale di BRouter (buildSrc/.../brouter.java-conventions.gradle:
    // "options.release = 11") — mantenuto identico per restare fedeli al bytecode gia' verificato
    // funzionante su Android ART nel prototipo (Fase 10), non un valore scelto a caso.
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

val brouterModules = listOf("brouter-codec", "brouter-core", "brouter-expressions", "brouter-mapaccess", "brouter-util")
val brouterUpstream = rootProject.layout.projectDirectory.dir("third-party/brouter")
val brouterPatched = layout.buildDirectory.dir("generated/brouter")

/** Copia i sorgenti dei moduli di BRouter e ci applica le patch del progetto, in ordine di nome. */
abstract class PatchedSources @Inject constructor(
    private val fs: FileSystemOperations,
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val upstream: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val patches: ConfigurableFileCollection

    @get:Internal
    abstract val upstreamRoot: DirectoryProperty

    @get:Input
    abstract val modules: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile
        val root = upstreamRoot.get().asFile
        if (!root.resolve("brouter-core").isDirectory) {
            throw GradleException(
                "third-party/brouter e' vuota: scaricare i submodule con " +
                    "git -c core.longpaths=true submodule update --init --depth 1",
            )
        }
        fs.sync {
            from(root) { modules.get().forEach { include("$it/src/main/java/**") } }
            into(out)
        }
        for (patch in patches.files.sortedBy { it.name }) {
            // GIT_CEILING_DIRECTORIES: git apply non deve trovare il repository del progetto sopra la copia,
            // o leggerebbe i percorsi delle patch dalla sua root.
            val result = exec.exec {
                workingDir = out
                environment("GIT_CEILING_DIRECTORIES", out.parentFile.absolutePath)
                commandLine("git", "apply", patch.absolutePath)
                isIgnoreExitValue = true
            }
            if (result.exitValue != 0) {
                throw GradleException(
                    "La patch ${patch.name} non si applica a third-party/brouter (third-party/patches/brouter): " +
                        "rifarla sul nuovo codice o toglierla",
                )
            }
        }
    }
}

val brouterSources = tasks.register<PatchedSources>("brouterSources") {
    upstreamRoot.set(brouterUpstream)
    modules.set(brouterModules)
    upstream.from(brouterModules.map { brouterUpstream.dir("$it/src/main/java") })
    patches.from(rootProject.fileTree("third-party/patches/brouter") { include("*.patch") })
    outputDir.set(brouterPatched)
}

sourceSets {
    main {
        java.srcDir(brouterModules.map { m -> brouterSources.flatMap { it.outputDir.dir("$m/src/main/java") } })
    }
}
