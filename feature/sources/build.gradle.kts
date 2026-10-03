plugins {
    id("pockettravel.android.library")
    id("pockettravel.android.compose")
}

android {
    namespace = "com.pockettravel.feature.sources"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
}
