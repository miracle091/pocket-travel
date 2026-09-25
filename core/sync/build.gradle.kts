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
        buildConfigField("String", "MANIFEST_URL_OVERRIDE", "\"\"")
    }

    // Solo debug: manifest alternativo (es. un server locale per provare i pacchetti sull'emulatore),
    // passato con -PpocketTravel.manifestUrl=http://10.0.2.2:8000/manifest.json. Vuoto = manifest pubblicato.
    buildTypes {
        getByName("debug") {
            val manifestUrl = providers.gradleProperty("pocketTravel.manifestUrl").getOrElse("")
            buildConfigField("String", "MANIFEST_URL_OVERRIDE", "\"$manifestUrl\"")
        }
    }

    buildFeatures {
        buildConfig = true
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
    // Decompressione dei pacchetti POI .xz (xz-java, 0BSD, Java puro).
    implementation(libs.xz)
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
    // Solo per verificare a livello di schema che le query di GuidesImporter e
    // PoiImporter corrispondano alle colonne prodotte dalla pipeline dati (tools/data-pipeline) — non
    // esercita android.database.sqlite.SQLiteDatabase stesso, che richiede un device/
    // emulatore reale (nessun Robolectric aggiunto qui: il progetto non lo usa altrove).
    testImplementation("org.xerial:sqlite-jdbc:3.53.4.0")

    // Test strumentati (androidTest): eseguono GuidesImporter/PoiImporter
    // con android.database.sqlite reale su device/emulatore, colmando il limite dei test JVM sopra.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.okhttp.mockwebserver)
    // room-runtime non e' esposto da :core:data (dichiarato li' come implementation, non api) —
    // qui serve direttamente per chiamare Room.inMemoryDatabaseBuilder(...) nel test.
    androidTestImplementation(libs.room.runtime)
}
