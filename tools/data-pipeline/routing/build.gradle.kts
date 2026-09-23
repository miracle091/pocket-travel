// Genera i file .rd5 per BRouter tramite i tre stadi ufficiali di btools.mapcreator
// vendorizzati in :third-party:brouter-map-creator (OsmFastCutter -> PosUnifier -> WayLinker).
// Separato da :maptiles: dipendere da :maptiles solo per il piccolo convertitore OSM XML -> PBF
// trascinerebbe planetiler-core (bloat non necessario qui), percio' quelle due funzioni sono
// duplicate localmente in OsmXmlToPbf.kt.
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
    implementation(project(":third-party:brouter-map-creator"))
    implementation(libs.osmosis.osm.binary)

    testImplementation("junit:junit:4.13.2")
}

val pipelineRoot = projectDir.parentFile

tasks.register<JavaExec>("generateRoutingGraph") {
    group = "data-pipeline"
    workingDir = pipelineRoot
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pockettravel.pipeline.GenerateRoutingGraphKt")
}

tasks.test {
    workingDir = pipelineRoot
}
