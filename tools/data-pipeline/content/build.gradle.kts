// Genera i pacchetti di contenuto, file SQLite con colonne che rispecchiano le entity Room meno
// l'id autogenerato (non una replica dello schema Room dell'app): il formato "di trasporto" che
// gli importer lato app (core/sync) leggono riga per riga per popolare region.db.
// - guides.db (generateGuides): guide_sections + emergency_numbers di tutte le regioni, un solo
//   pacchetto aggiornato separatamente;
// - poi.db e poi-extra.db (generatePoi): i POI di una regione, base ed extra (vedi core:poi).
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
    // Regole dei POI condivise con l'app: quali vanno nel pacchetto base, quali nell'extra.
    implementation(project(":core:poi"))
    implementation(libs.sqlite.jdbc)
    // Lettura PMTiles locali e decodifica dei tile vettoriali per GenerateAddresses.kt.
    implementation(libs.planetiler.core)

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

registerPipelineTask("generateGuides", "com.pockettravel.pipeline.GenerateGuideContentKt", usesSqlite = true)
// L'unico task che legge l'XML OSM grezzo di un'intera nazione (es. Germania, Stati Uniti): oltre
// alla lettura in streaming di readPois (tiene in memoria solo i POI, non i nodi con tutti i tag),
// un margine di heap esplicito assorbe nazioni ancora piu' grandi/dense di POI senza dipendere
// dal default della JVM (visto: OutOfMemoryError sul default generando i POI per l'Italia).
registerPipelineTask("generatePoi", "com.pockettravel.pipeline.GeneratePoiKt", maxHeap = "4g", usesSqlite = true)
registerPipelineTask("generateAddresses", "com.pockettravel.pipeline.GenerateAddressesKt", maxHeap = "4g", usesSqlite = true)
registerPipelineTask("generateWorldMap", "com.pockettravel.pipeline.GenerateWorldMapKt")
registerPipelineTask("generateManifest", "com.pockettravel.pipeline.GenerateManifestKt")
registerPipelineTask("mergeManifests", "com.pockettravel.pipeline.MergeManifestsKt")
registerPipelineTask("validateManifest", "com.pockettravel.pipeline.ValidateManifestKt")

tasks.test {
    workingDir = pipelineRoot
}
