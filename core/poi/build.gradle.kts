// Categorie dei POI e regole su cosa mostrare e cosa pubblicare: Kotlin puro, senza Android, cosi'
// lo usano sia l'app (core:data, mappa) sia la pipeline (tools:data-pipeline:content, generatePoi)
// e le due non possono disallinearsi.
plugins {
    id("org.jetbrains.kotlin.jvm")
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
    testImplementation("junit:junit:4.13.2")
}
