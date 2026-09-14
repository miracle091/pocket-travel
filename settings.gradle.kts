pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Richiesto da planetiler-core (gt-shapefile/gt-epsg-hsql, dipendenze GeoTools
        // non pubblicate su Maven Central) — usato solo da :tools:data-pipeline.
        maven("https://repo.osgeo.org/repository/release/")
    }
}

rootProject.name = "pocket-travel"

include(
    ":app",
    ":core:data",
    ":core:content",
    ":core:sync",
    ":core:ui",
    ":feature:guide",
    ":feature:map",
    ":feature:ai",
    ":feature:sources",
    ":feature:vault",
    ":tools:data-pipeline:maptiles",
    ":tools:data-pipeline:routing",
    ":tools:data-pipeline:content",
    ":third-party:brouter-core",
    ":third-party:brouter-map-creator",
)
