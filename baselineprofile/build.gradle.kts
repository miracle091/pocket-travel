// Genera il baseline profile dell'app su un emulatore o un telefono con API 33 o piu' recente:
// ./gradlew :app:generateReleaseBaselineProfile. Il profilo va in app/src/release/generated/baselineProfiles
// e si committa; la CI e publish-apk lo usano cosi' com'e', senza rigenerarlo.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.pockettravel.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.benchmark.macro.junit4)
}
