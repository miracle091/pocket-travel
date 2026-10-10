plugins {
    id("pockettravel.android.application")
    id("pockettravel.android.compose")
    id("pockettravel.hilt")
    // Rotte type-safe di Navigation Compose (PocketTravelDestinations).
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.pockettravel.app"

    defaultConfig {
        applicationId = "com.pockettravel.app"
        versionCode = 11
        versionName = "0.10.0"
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
            // R8: codice e risorse non usati tolti, classi ottimizzate (avvio piu' rapido, APK piu' piccolo).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // La lingua si sceglie dentro l'app (AppLanguage): un bundle diviso per lingua non avrebbe le
    // stringhe delle lingue diverse da quella del telefono.
    bundle {
        language {
            enableSplit = false
        }
    }

    // feature:ai compila llama.cpp con GGML_BACKEND_DL=ON: i backend CPU (libggml-cpu-*.so) sono
    // caricati con dlopen da applicationInfo.nativeLibraryDir, che resta vuota se le .so non vengono
    // estratte dall'APK (default AGP, extractNativeLibs=false) — "no backends are loaded" al load().
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Timber arriva solo come dipendenza transitiva di MapLibre, insieme al suo controllo lint:
    // il progetto usa android.util.Log ovunque, quindi "usa Timber" non si applica.
    lint {
        disable += "LogNotTimber"
    }
}

dependencies {
    implementation(project(":core:data"))
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
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3.adaptive.navigation.suite)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.fragment.ktx)

    testImplementation(libs.junit)

    androidTestImplementation(libs.navigation.testing)
    // L'APK dei test usa le stesse versioni dell'app (consistent resolution di AGP): ui-test-junit4 e
    // navigation-testing chiedono versioni un po' piu' recenti di queste due librerie, quindi l'app le alza.
    constraints {
        implementation("androidx.concurrent:concurrent-futures:1.2.0")
        implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")
        implementation("com.google.errorprone:error_prone_annotations:2.30.0")
    }
}
