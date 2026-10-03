plugins {
    id("pockettravel.android.library")
    id("pockettravel.android.compose")
    id("pockettravel.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.pockettravel.feature.guide"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    // Meteo da Open-Meteo (WeatherRepository): client HTTP e Json sono quelli forniti da core:sync (SyncModule).
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
