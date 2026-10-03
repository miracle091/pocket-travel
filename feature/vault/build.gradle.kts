plugins {
    id("pockettravel.android.library")
    id("pockettravel.android.compose")
    id("pockettravel.hilt")
}

android {
    namespace = "com.pockettravel.feature.vault"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    testImplementation(libs.junit)
}
