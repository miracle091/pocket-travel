plugins {
    id("pockettravel.android.library")
    id("pockettravel.android.compose")
    id("pockettravel.hilt")
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

    testImplementation(libs.junit)
}
