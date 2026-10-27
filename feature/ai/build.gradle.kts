plugins {
    id("pockettravel.android.library")
    id("pockettravel.android.compose")
    id("pockettravel.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.pockettravel.feature.ai"
    // Fake del motore sul dispositivo e dei modelli, per i test JVM dei ViewModel di app.
    testFixtures { enable = true }

    // NDK 29 stabile, non l'rc1 usato da examples/llama.android: la release finale ha sostituito l'rc
    // con lo stesso major.
    ndkVersion = "29.0.14206865"

    // Il riferimento (examples/llama.android, com.arm.aichat) richiede minSdk 33; qui resta il minSdk 26
    // del progetto (pockettravel.android.library): qualche API NDK/Kotlin potrebbe non funzionare sotto API 33.
    // Gli ABI per build type: la release compila solo arm64-v8a (i telefoni), il debug anche x86_64 per
    // l'emulatore dei test strumentati. Senza abiFilters CMake compilerebbe anche x86 e armeabi-v7a.
    buildTypes {
        debug {
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        release {
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_APP=OFF"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_OPENSSL=OFF"

                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
                arguments += "-DGGML_LLAMAFILE=OFF"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
            // Non in .cxx/: su Windows un file watcher che tiene aperte le sottocartelle (chokidar con fs.watch,
            // come quello dell'app Hive) fa fallire il rename di FetchContent ("file RENAME failed ...
            // kleidiai-src: Accesso negato"). Le cartelle .cache/ vengono di solito ignorate dai watcher
            // e, a differenza di build/, `clean` non le cancella, quindi la cache di CMake resta.
            buildStagingDirectory = file(".cache/cxx")
        }
    }

    // Vale solo per l'APK dei test strumentati di questo modulo: per l'app lo stesso blocco sta in
    // app/build.gradle.kts, con il motivo (backend GGML caricati via dlopen da nativeLibraryDir).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:speech"))
    implementation(project(":core:sync"))
    implementation(project(":core:ui"))
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testFixturesImplementation(libs.kotlinx.coroutines.android)
    // FakeAiModelUpdates espone AiModelManifestEntry (core:sync) e WorkInfo nella firma.
    testFixturesApi(project(":core:sync"))
    testFixturesApi(libs.work.runtime.ktx)
    // Il plugin del compilatore Compose del modulo vale anche per i fake e richiede il runtime.
    testFixturesImplementation(platform(libs.compose.bom))
    testFixturesImplementation(libs.compose.ui)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core:data")))
    testImplementation(testFixtures(project(":core:sync")))
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // Cronologia in memoria (FakeConversationDao, chiavi software) per la ripresa in MultiTurnDeviceTest.
    androidTestImplementation(testFixtures(project(":core:data")))
}
