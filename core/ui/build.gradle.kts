plugins {
    id("pockettravel.android.library")
    id("pockettravel.android.compose")
}

android {
    namespace = "com.pockettravel.core.ui"
}

dependencies {
    implementation(project(":core:poi"))
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
