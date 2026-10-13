plugins {
    id("pockettravel.android.library")
    id("pockettravel.android.compose")
    id("pockettravel.hilt")
}

android {
    namespace = "com.pockettravel.feature.map"
    // Fake di PoiDao, GPS, tile e scheduler, per i test JVM del modulo.
    testFixtures { enable = true }

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

    // Tipi nelle firme dei fake: PoiDao e Flow.
    testFixturesApi(project(":core:data"))
    testFixturesApi(libs.kotlinx.coroutines.android)
    // Il plugin del compilatore Compose del modulo vale anche per i fake e richiede il runtime.
    testFixturesImplementation(platform(libs.compose.bom))
    testFixturesImplementation(libs.compose.ui)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core:data")))
    testImplementation(testFixtures(project(":feature:map")))

    // Test strumentato (androidTest): verifica su device/emulatore reale che il wiring di
    // RouteEngineModule (copia profilo dagli asset, RoutingContext/RoutingEngine di
    // :third-party:brouter-core) non crashi su Android ART.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
