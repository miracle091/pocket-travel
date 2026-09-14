plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt.plugin)
}

android {
    namespace = "com.pockettravel.feature.map"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation(project(":third-party:brouter-core"))
    implementation(libs.maplibre.android.sdk)
    implementation(libs.maplibre.annotation.plugin)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // android-plugin-annotation-v9 tira dentro androidx.appcompat:appcompat:1.0.0 (2018), mai
    // aggiornato dal suo POM, che a sua volta porta l'intera famiglia androidx.legacy a 1.0.0 —
    // versioni in cui vectordrawable e vectordrawable-animated condividevano lo stesso namespace
    // nel manifest (bug risolto da Google in versioni successive). AGP rifiuta il manifest merge
    // finche' non sono forzate a una versione dove il namespace e' unico per artefatto.
    constraints {
        implementation(libs.androidx.vectordrawable)
        implementation(libs.androidx.vectordrawable.animated)
    }

    testImplementation("junit:junit:4.13.2")

    // Test strumentato (androidTest): verifica su device/emulatore reale che il wiring di
    // RouteEngineModule (copia profilo dagli asset, RoutingContext/RoutingEngine di
    // :third-party:brouter-core) non crashi su Android ART — stesso livello a cui GraphHopper
    // falliva (vedi README Fase 9/10).
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
