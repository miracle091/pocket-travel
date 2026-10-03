plugins {
    id("pockettravel.android.library")
    id("pockettravel.android.compose")
    id("pockettravel.hilt")
}

android {
    namespace = "com.pockettravel.feature.map"

    buildFeatures {
        // BuildConfig.DEBUG: il percorso nel log solo in debug, per la simulazione GPS sull'emulatore.
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    implementation(project(":third-party:brouter-core"))
    implementation(libs.maplibre.android.sdk)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    // Richiesta del permesso di posizione dalla schermata di navigazione.
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)

    // Test strumentato (androidTest): verifica su device/emulatore reale che il wiring di
    // RouteEngineModule (copia profilo dagli asset, RoutingContext/RoutingEngine di
    // :third-party:brouter-core) non crashi su Android ART.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
