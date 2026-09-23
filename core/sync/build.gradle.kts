plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.plugin)
}

android {
    namespace = "com.pockettravel.core.sync"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.work.runtime.ktx)
    implementation(libs.okhttp)
    // Lettura PMTiles via HTTP range request (BSD-3, verificato) per estrarre lato device solo
    // le tile della bounding box di una regione dalla build pubblica Protomaps — vedi
    // PmtilesExtractor. Compatibile Android (dichiarato dal progetto fino ad API 16).
    implementation("ch.poole.geo.pmtiles-reader:Reader:0.3.7")
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.okhttp.mockwebserver)
    // Solo per verificare a livello di schema che le query di RegionContentImporter
    // corrispondano alle colonne prodotte dalla pipeline dati (tools/data-pipeline) — non
    // esercita android.database.sqlite.SQLiteDatabase stesso, che richiede un device/
    // emulatore reale (nessun Robolectric aggiunto qui: il progetto non lo usa altrove).
    testImplementation("org.xerial:sqlite-jdbc:3.53.4.0")

    // Test strumentati (androidTest): eseguono RegionContentImporter/RegionRoutingGraphInstaller
    // con android.database.sqlite reale su device/emulatore, colmando il limite dei test JVM sopra.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // room-runtime non e' esposto da :core:data (dichiarato li' come implementation, non api) —
    // qui serve direttamente per chiamare Room.inMemoryDatabaseBuilder(...) nel test.
    androidTestImplementation(libs.room.runtime)
}
