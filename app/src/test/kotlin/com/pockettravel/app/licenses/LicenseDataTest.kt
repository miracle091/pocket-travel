package com.pockettravel.app.licenses

import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Controlla `res/raw/aboutlibraries.json` generato dal plugin AboutLibraries per debug e release (i test dipendono
 * dai task che lo generano, app/build.gradle.kts).
 */
class LicenseDataTest {

    private val variants = listOf("debug", "release").associateWith { variant ->
        val json = File("build/generated/aboutLibraries/$variant/res/raw/aboutlibraries.json")
        assertTrue("manca $json", json.isFile)
        Libs.Builder().withJson(json.readText()).build().libraries
    }

    private fun forEachVariant(check: (String, List<Library>) -> Unit) = variants.forEach { (variant, libraries) -> check(variant, libraries) }

    @Test
    fun `ogni componente fuori da Gradle ha la sua definizione`() = forEachVariant { variant, libraries ->
        val ids = libraries.map { it.uniqueId }.toSet()

        val missing = outsideGradle - ids

        assertTrue("$variant: senza definizione $missing", missing.isEmpty())
    }

    @Test
    fun `ogni voce ha il testo completo di una licenza`() = forEachVariant { variant, libraries ->
        val withoutText = libraries.filter { library ->
            val shown = library.shownLicenses()
            shown.isEmpty() || shown.any { it.licenseContent.isNullOrBlank() }
        }

        assertTrue("$variant: senza testo ${withoutText.map { it.uniqueId }}", withoutText.isEmpty())
    }

    @Test
    fun `le licenze che chiedono l'avviso di copyright mostrano il testo del componente`() = forEachVariant { variant, libraries ->
        // MIT e BSD chiedono l'avviso di copyright insieme al testo: il testo generico ha solo un segnaposto.
        // Una dipendenza nuova con una di queste licenze fallisce qui finché non ha la sua definizione a mano.
        val generic = libraries.filter { library ->
            library.shownLicenses().any { it.spdxId in noticeLicenses && it.hash == it.spdxId }
        }

        assertTrue("$variant: senza avviso di copyright ${generic.map { it.uniqueId }}", generic.isEmpty())
    }

    @Test
    fun `nomi e note nella lingua dell'app sono di voci esistenti`() = forEachVariant { variant, libraries ->
        val ids = libraries.map { it.uniqueId }.toSet()

        assertTrue("$variant: ${licenseLabels.keys - ids}", ids.containsAll(licenseLabels.keys))
    }

    @Test
    fun `modelli e dati hanno nome o nota nella lingua dell'app`() = forEachVariant { variant, libraries ->
        val unlabeled = libraries.filter { it.group() != LicenseGroup.SOFTWARE && it.uniqueId !in licenseLabels }

        assertTrue("$variant: ${unlabeled.map { it.uniqueId }}", unlabeled.isEmpty())
    }

    @Test
    fun `il testo del componente sostituisce le licenze generiche del POM`() = forEachVariant { _, libraries ->
        val pmtiles = libraries.first { it.uniqueId == "ch.poole.geo.pmtiles-reader:Reader" }
        val camera = libraries.first { it.uniqueId == "androidx.camera:camera-core" }

        // Il POM di pmtiles-reader dichiara BSD-3-Clause, il suo LICENCE.txt è MIT (VarInt.java Apache-2.0).
        assertEquals(setOf("MIT", "Apache-2.0"), pmtiles.shownLicenses().map { it.spdxId }.toSet())
        assertEquals(setOf("Apache-2.0", "BSD-3-Clause"), camera.shownLicenses().map { it.spdxId }.toSet())
        assertTrue(camera.shownLicenses().any { "LibYuv" in it.licenseContent.orEmpty() })
    }

    @Test
    fun `modelli e dati stanno nelle loro sezioni`() = forEachVariant { _, libraries ->
        val byId = libraries.associateBy { it.uniqueId }

        assertEquals(LicenseGroup.MODELS, byId.getValue("pockettravel.models:qwen").group())
        assertEquals(LicenseGroup.DATA, byId.getValue("pockettravel.data:openstreetmap").group())
        assertEquals(LicenseGroup.SOFTWARE, byId.getValue("org.ggml:llama.cpp").group())
    }

    private companion object {
        val noticeLicenses = setOf("MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC")

        // Nell'APK o usati dall'app ma fuori dalle dipendenze Gradle: definizioni in app/aboutlibraries/libraries.
        val outsideGradle = setOf(
            "org.ggml:llama.cpp", "com.github.nlohmann:json", "com.github.yhirose:cpp-httplib", "com.arm:kleidiai",
            "org.llvm:openmp", "btools:brouter", "com.google:material-symbols", "com.google:noto-sans",
            "org.publicsuffix:list", "pockettravel.models:qwen", "pockettravel.models:trained",
            "pockettravel.data:openstreetmap", "pockettravel.data:protomaps", "pockettravel.data:natural-earth",
            "pockettravel.data:airports", "pockettravel.data:flags", "pockettravel.data:wikivoyage",
            "pockettravel.data:wikipedia", "pockettravel.data:wikidata", "pockettravel.data:weather",
            "pockettravel.data:emergency-canada", "pockettravel.data:emergency-uk",
            "pockettravel.data:travel-advice-canada", "pockettravel.data:travel-advice-uk",
            "pockettravel.data:vaccinations-canada", "pockettravel.data:vaccinations-uk",
            "pockettravel.data:vaccinations-fcdo", "pockettravel.data:vaccinations-france",
        )
    }
}
