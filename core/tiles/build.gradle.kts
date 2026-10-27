// Id delle tile PMTiles di un rettangolo (curva di Hilbert, a blocchi): Kotlin puro, senza Android, cosi'
// lo usano sia l'app (core:sync, estrazione della mappa) sia la pipeline (tools:data-pipeline:content,
// impronta della mappa) e le due non possono disallinearsi.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("pockettravel.detekt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Solo per confrontare la curva di Hilbert con quella della libreria che legge gli archivi nell'app.
    testImplementation(libs.pmtiles.reader)
    testImplementation(libs.junit)
}
