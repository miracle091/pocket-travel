plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.plugin)
}

android {
    namespace = "com.pockettravel.feature.ai"
    compileSdk = 37

    // NDK 29 stabile, non l'rc1 usato da examples/llama.android: la release finale ha sostituito l'rc
    // con lo stesso major.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        // Il riferimento (examples/llama.android, com.arm.aichat) richiede minSdk 33; qui resta 26, come
        // il resto del progetto: qualche API NDK/Kotlin potrebbe non funzionare sotto API 33.
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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

    buildFeatures {
        compose = true
    }

    // Vale solo per l'APK dei test strumentati di questo modulo: per l'app lo stesso blocco sta in
    // app/build.gradle.kts, con il motivo (backend GGML caricati via dlopen da nativeLibraryDir).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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
    implementation(project(":core:sync"))
    implementation(project(":core:ui"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.hilt.android)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
