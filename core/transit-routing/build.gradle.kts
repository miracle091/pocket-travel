// Viaggi con i mezzi pubblici su una rete GTFS (RAPTOR): Kotlin puro, senza Android, cosi' lo usano l'app (core:data
// legge transit.db) e la pipeline (tools:data-pipeline:content, test su un feed vero) con lo stesso codice.
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
    testImplementation(libs.junit)
}
