plugins {
    id("pockettravel.android.library")
    id("pockettravel.android.compose")
    id("pockettravel.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.pockettravel.feature.ai"

    // NDK 29 stabile, non l'rc1 usato da examples/llama.android: la release finale ha sostituito l'rc
    // con lo stesso major.
    ndkVersion = "29.0.14206865"

    // Il riferimento (examples/llama.android, com.arm.aichat) richiede minSdk 33; qui resta il minSdk 26
    // del progetto (pockettravel.android.library): qualche API NDK/Kotlin potrebbe non funzionare sotto API 33.
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
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
    implementation(project(":core:sync"))
    implementation(project(":core:ui"))
    implementation(libs.compose.ui.tooling.preview)
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

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
