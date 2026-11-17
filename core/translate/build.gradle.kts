plugins {
    id("pockettravel.android.library")
}

android {
    namespace = "com.pockettravel.core.translate"

    // Stesso NDK di feature/ai.
    ndkVersion = "29.0.14206865"

    // Gli ABI come in feature/ai: la release compila solo arm64-v8a, il debug anche x86_64 per l'emulatore.
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
                // Solo la libreria JNI: il resto di bergamot (eseguibili, bench di Eigen) non serve e a volte non compila.
                targets += "bergamot-jni"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
            // Fuori da .cxx/ e da build/ per lo stesso motivo di feature/ai (watcher dei file su Windows, e `clean`
            // non cancella la cache di CMake).
            buildStagingDirectory = file(".cache/cxx")
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
