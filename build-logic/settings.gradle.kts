// Convention plugin condivisi dai moduli Android (vedi convention/): compileSdk, minSdk, Java/Kotlin
// target, Compose e Hilt sono definiti una volta sola invece che in ogni build.gradle.kts.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
