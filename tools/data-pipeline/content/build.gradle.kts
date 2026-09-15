// Genera content.db: file SQLite con le tabelle guide_sections e poi (colonne che
// rispecchiano GuideSectionEntity/PoiEntity meno l'id autogenerato), non una replica dello
// schema Room dell'app — e' il formato "di trasporto" che l'importer lato app (core/sync)
// legge riga per riga per popolare region.db via GuideDao/PoiDao.insertAll(). Un solo file
// al posto dei due file separati guide.db/poi.db di prima (README Fase 14): generateGuideContent
// e generatePoi restano due task/entry point separati ma scrivono nello stesso file, in
// qualunque ordine vengano eseguiti (vedi i commenti in GenerateGuideContent.kt/GeneratePoi.kt).
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    // Riuso di OsmData/parseOsmXml (nessun conflitto hppc: questo modulo non tocca
    // GraphHopper).
    implementation(project(":tools:data-pipeline:maptiles"))
    implementation(libs.sqlite.jdbc)

    testImplementation("junit:junit:4.13.2")
    // Usato anche da MergeManifests.kt (parsing/merge di manifest.json reali), non solo dai
    // test — senza tirare dentro il plugin kotlinx.serialization solo per questo.
    implementation("org.json:json:20260814")
}

val pipelineRoot = projectDir.parentFile

fun registerPipelineTask(name: String, mainClass: String, maxHeap: String? = null, usesSqlite: Boolean = false) {
    tasks.register<JavaExec>(name) {
        group = "data-pipeline"
        workingDir = pipelineRoot
        classpath = sourceSets["main"].runtimeClasspath
        this.mainClass.set(mainClass)
        maxHeap?.let { maxHeapSize = it }
        // sqlite-jdbc carica una libreria nativa via System::load: dal JDK 24 in poi questo e'
        // un "restricted method" che stampa un warning (bloccato del tutto in una release
        // futura) a meno di dichiarare esplicitamente l'accesso nativo per questo modulo.
        if (usesSqlite) jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}

registerPipelineTask("generateGuideContent", "com.pockettravel.pipeline.GenerateGuideContentKt", usesSqlite = true)
// L'unico task che legge l'XML OSM grezzo di un'intera nazione (es. Italia, Stati Uniti): oltre
// alla conversione DOM->SAX di parseOsmXml (che gia' evita di tenere l'albero XML in memoria),
// un margine di heap esplicito assorbe nazioni ancora piu' grandi/dense di POI senza dipendere
// dal default della JVM (visto: OutOfMemoryError sul default generando content.db per l'Italia).
registerPipelineTask("generatePoi", "com.pockettravel.pipeline.GeneratePoiKt", maxHeap = "4g", usesSqlite = true)
registerPipelineTask("generateManifest", "com.pockettravel.pipeline.GenerateManifestKt")
registerPipelineTask("mergeManifests", "com.pockettravel.pipeline.MergeManifestsKt")
registerPipelineTask("validateManifest", "com.pockettravel.pipeline.ValidateManifestKt")

tasks.test {
    workingDir = pipelineRoot
}
