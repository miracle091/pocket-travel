plugins {
    id("pockettravel.android.library")
    id("pockettravel.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.pockettravel.core.data"
}

// Schemi Room esportati (vedi ksp room.schemaLocation), letti da MigrationTestHelper nei test di migrazione.
androidComponents {
    onVariants { variant ->
        variant.androidTest?.sources?.assets?.addStaticSourceDirectory("schemas")
    }
}

dependencies {
    // PoiCategory e regole dei POI, condivise con la pipeline: api perche' fanno parte dell'API di Poi.
    api(project(":core:poi"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    // Esegue le query di TransitBoard su un transit.db sintetico (android.database.sqlite non c'e' nei test JVM).
    testImplementation(libs.sqlite.jdbc)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
