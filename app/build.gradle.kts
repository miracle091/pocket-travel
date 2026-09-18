plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt.plugin)
}

android {
    namespace = "com.pockettravel.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pockettravel.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.0"
    }

    // Le credenziali arrivano da variabili d'ambiente (secrets del workflow CI, mai committate):
    // se assenti (build locale di sviluppo) la release resta semplicemente non firmata.
    signingConfigs {
        create("release") {
            val storePath = System.getenv("RELEASE_KEYSTORE_PATH")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (System.getenv("RELEASE_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
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
    implementation(project(":core:content"))
    implementation(project(":core:sync"))
    implementation(project(":core:ui"))
    implementation(project(":feature:guide"))
    implementation(project(":feature:map"))
    implementation(project(":feature:ai"))
    implementation(project(":feature:sources"))
    implementation(project(":feature:vault"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.fragment.ktx)
    ksp(libs.hilt.compiler)

    testImplementation("junit:junit:4.13.2")
}
