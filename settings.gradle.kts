pluginManagement {
    includeBuild("build-logic")
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
        // non pubblicate su Maven Central) — usato solo da :tools:data-pipeline. Solo i gruppi che mancano su Maven
        // Central, cosi' il repository non puo' servire altre librerie.
        maven("https://repo.osgeo.org/repository/release/") {
            content {
                includeGroupByRegex("org\\.geotools(\\..+)?")
                includeGroup("org.eclipse.imagen")
                includeGroup("it.geosolutions.jgridshift")
            }
        }
    }
}

rootProject.name = "pocket-travel"

include(
    ":app",
    ":baselineprofile",
    ":core:data",
    ":core:sync",
    ":core:ui",
    ":core:poi",
    ":core:tiles",
    ":core:routing-engine",
    ":core:transit-routing",
    ":core:speech",
    ":core:translate",
    ":feature:guide",
    ":feature:map",
    ":feature:ai",
    ":feature:sources",
    ":feature:vault",
    ":tools:data-pipeline:content",
)
