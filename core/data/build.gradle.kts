plugins {
    id("pockettravel.android.library")
    id("pockettravel.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.pockettravel.core.data"
    // InMemorySharedPreferences e InMemoryPreferencesFactory, per i test JVM di tutti i moduli.
    testFixtures { enable = true }
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

    // Flow nelle firme dei fake (FakeRegionRepository).
    testFixturesApi(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core:data")))
    // Esegue le query di TransitBoard su un transit.db sintetico (android.database.sqlite non c'e' nei test JVM).
    testImplementation(libs.sqlite.jdbc)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
    // Chiavi software e preferenze in memoria per le misure della cronologia (ConversationHistoryDeviceTest).
    androidTestImplementation(testFixtures(project(":core:data")))
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// I test JVM usano sqlite-jdbc, che carica una libreria nativa con System::load: dal JDK 24 e' un "restricted method"
// che stampa un warning (e sara' bloccato in una release futura) senza l'accesso nativo dichiarato.
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
