// Genera map.pmtiles (Planetiler, profilo Shortbread minimale). Separato dal sottoprogetto
// :routing perche' planetiler-core e graphhopper-core richiedono versioni incompatibili
// di com.carrotsearch:hppc (verificato: forzarne una sola rompe l'altro a runtime con
// NoClassDefFoundError) — isolarli in due classpath Gradle distinti evita l'hack.
plugins {
    // Senza versione: AGP 9.4.0 (compilatore Kotlin integrato) mette gia' questo plugin
    // sul classpath del build a una versione fissa — dichiararne una diversa qui fa
    // fallire la risoluzione ("plugin gia' sul classpath con versione sconosciuta").
    id("org.jetbrains.kotlin.jvm")
}

java {
    // 21, non 17 come i moduli app: planetiler-core porta in transitiva org.maplibre:mlt,
    // che richiede runtime 21+. Nessun vincolo minSdk qui (modulo JVM puro, non Android).
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.planetiler.core)
    implementation(libs.osmosis.osm.binary)

    testImplementation("junit:junit:4.13.2")
}

// Le testdata sono condivise con :routing e vivono in tools/data-pipeline/testdata/.
val pipelineRoot = projectDir.parentFile

tasks.register<JavaExec>("generateMapTiles") {
    group = "data-pipeline"
    workingDir = pipelineRoot
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pockettravel.pipeline.GenerateMapTilesKt")
}

tasks.test {
    workingDir = pipelineRoot
}
