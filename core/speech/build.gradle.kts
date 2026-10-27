plugins {
    id("pockettravel.android.library")
    id("pockettravel.hilt")
}

android {
    namespace = "com.pockettravel.core.speech"
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
