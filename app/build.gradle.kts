plugins {
    id("pockettravel.android.application")
    id("pockettravel.android.compose")
    id("pockettravel.hilt")
    // Rotte type-safe di Navigation Compose (PocketTravelDestinations).
    alias(libs.plugins.kotlin.serialization)
    // Schermata Licenze: elenco delle dipendenze con i testi delle licenze, generato al build (res/raw/aboutlibraries.json).
    alias(libs.plugins.aboutlibraries.android)
}

android {
    namespace = "com.pockettravel.app"

    // AGP toglie i simboli di debug dalle .so (task stripDebugSymbols) solo se trova l'NDK: senza questa
    // riga le librerie di llama.cpp restavano con .debug_info (decine di MB per ABI). Stessa versione di feature:ai.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.pockettravel.app"
        versionCode = 13
        versionName = "0.12.0"
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
        // Gli ABI non compaiono nei default di defaultConfig: ogni build type ha il suo elenco.
        // x86_64 solo nel debug, per gli emulatori dei test strumentati (docs/GUIDA.md). MapLibre porta
        // anche x86 e armeabi-v7a: qui fuori dall'APK.
        debug {
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        release {
            ndk {
                abiFilters += "arm64-v8a"
            }
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
            // Arrivano gia' senza simboli dalle loro AAR (MapLibre, androidx.graphics): AGP non riesce a passarle
            // a llvm-strip e lo segnala a ogni build ("Unable to strip"); saltarle non cambia l'APK.
            keepDebugSymbols += setOf("**/libmaplibre.so", "**/libandroidx.graphics.path.so")
        }
    }

    // Timber arriva solo come dipendenza transitiva di MapLibre, insieme al suo controllo lint:
    // il progetto usa android.util.Log ovunque, quindi "usa Timber" non si applica.
    lint {
        disable += "LogNotTimber"
    }
}

// Schermata Licenze: il plugin scrive per ogni variante res/raw/aboutlibraries.json con le dipendenze dei classpath
// della variante e le definizioni a mano di app/aboutlibraries (libraries/ per i componenti fuori da Gradle e gli
// avvisi di copyright, licenses/ per i testi). Senza rete: i testi delle licenze stanno tutti in licenses/.
aboutLibraries {
    offlineMode = true
    collect {
        configPath = file("aboutlibraries")
    }
    // Una dipendenza con una licenza fuori da questo elenco fa fallire il build: una licenza di codice va controllata
    // prima di aggiungerla qui. Le licenze di modelli e dati vengono dalle loro definizioni (sotto): una fonte nuova
    // entra con i suoi due file in app/aboutlibraries. LicenseDataTest controlla che ogni voce abbia un testo di
    // licenza e che ogni definizione arrivi nella schermata.
    license {
        strictMode = com.mikepenz.aboutlibraries.plugin.StrictMode.FAIL
        allowedLicenses.addAll(
            "Apache-2.0", "Apache-2.0 WITH LLVM-exception", "MIT", "BSD-2-Clause", "BSD-3-Clause", "0BSD", "MPL-2.0", "OFL-1.1",
        )
        allowedLicenses.addAll(dataAndModelLicenses())
    }
}

// Licenze delle definizioni con tag "data" o "models" di app/aboutlibraries/libraries: sono le fonti scelte a mano,
// ognuna con il suo testo in licenses/. Le licenze di codice restano nell'elenco esplicito sopra.
fun dataAndModelLicenses(): List<String> =
    file("aboutlibraries/libraries").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }.flatMap { f ->
        @Suppress("UNCHECKED_CAST")
        val definition = groovy.json.JsonSlurper().parse(f) as Map<String, Any?>
        if (definition["tag"] in setOf("data", "models")) (definition["licenses"] as List<String>) else emptyList()
    }.distinct()

// LicenseDataTest legge i JSON generati per debug e release.
tasks.withType<Test>().configureEach {
    inputs.files(tasks.named("prepareLibraryDefinitionsDebug"), tasks.named("prepareLibraryDefinitionsRelease"))
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
    implementation(libs.aboutlibraries.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // aboutlibraries-core legge il JSON con org.json, che nel android.jar dei test JVM ha solo gli stub.
    testImplementation(libs.org.json)
    testImplementation(testFixtures(project(":core:data")))
    testImplementation(testFixtures(project(":core:sync")))
    testImplementation(testFixtures(project(":feature:ai")))

    androidTestImplementation(libs.navigation.testing)
    // L'APK dei test usa le stesse versioni dell'app (consistent resolution di AGP): ui-test-junit4 e
    // navigation-testing chiedono versioni un po' piu' recenti di queste due librerie, quindi l'app le alza.
    constraints {
        implementation(libs.androidx.concurrent.futures)
        implementation(libs.androidx.concurrent.futures.ktx)
        implementation(libs.error.prone.annotations)
        // media3 tira dentro guava 33.3.1-android, con una CVE nota (CVE-2026-102554).
        implementation(libs.guava.android)
    }
}
