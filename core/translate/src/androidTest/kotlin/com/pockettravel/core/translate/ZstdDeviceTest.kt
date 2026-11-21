package com.pockettravel.core.translate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest

/**
 * Il decompressore zstd nativo decomprime file veri della CDN di Firefox (collezione `translations-models-v2`,
 * it->en base-memory) e il risultato ha lo sha256 pubblicato nel registro (decompressedHash). Il file compresso si
 * cerca in getExternalFilesDir(null)/bergamot/zst/<nome>.zst dell'app di test (copiato a mano, come i modelli di
 * BergamotTranslatorDeviceTest); se manca si scarica da
 * https://firefox-settings-attachments.cdn.mozilla.net/, e senza rete il test si salta. Si lancia con
 * installDebugAndroidTest e am instrument, mai con connectedDebugAndroidTest: disinstalla l'app di test e cancella
 * i file copiati.
 */
@RunWith(AndroidJUnit4::class)
class ZstdDeviceTest {

    private class Sample(
        val name: String,
        val attachment: String,
        val compressedSha256: String,
        val bytes: Long,
        val sha256: String,
    )

    private val vocabulary = Sample(
        "vocab.iten.spm",
        "main-workspace/translations-models-v2/352a4445-9c4b-43df-a292-c76cb040f428.zst",
        "ad75fd13305e6dc6ec6cc95e43fbb3cb2fe8b7ea60332f195785541325555db8",
        812_724,
        "3ef0211d4ae6db21440892f180f2019fe2bfc110a330ffa9d2eca9665e4f2bc5",
    )

    // 22,9 MB compressi in 31,6 MB: attraversa molti blocchi dei buffer del decompressore.
    private val model = Sample(
        "model.iten.intgemm.alphas.bin",
        "main-workspace/translations-models-v2/24527d9a-7ab8-40e3-a240-f894e0244371.zst",
        "5d3d5a8c463cd9f3a35d99d72165281f66c4637d5297c15418fe1e7cda49856a",
        31_561_787,
        "21b70978ce2f3b4da7a06b5de86a09abe3acd30b9eee1b2ebb3582b9bad790bf",
    )

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val copiedDirectory get() = File(checkNotNull(context.getExternalFilesDir(null)), "bergamot/zst")
    private val workDirectory get() = File(context.cacheDir, "zstd-test").apply { mkdirs() }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    // Il file compresso, controllato con lo sha256 del registro; salta il test se non c'e' e non si scarica.
    private fun compressed(sample: Sample): File {
        val copied = File(copiedDirectory, "${sample.name}.zst")
        val file = if (copied.isFile) copied else File(workDirectory, "${sample.name}.zst").also { target ->
            try {
                URL(CDN + sample.attachment).openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            } catch (_: IOException) {
                target.delete()
            }
        }
        assumeTrue("File ${sample.name}.zst assente in $copiedDirectory e download non riuscito", file.isFile)
        assertEquals(sample.compressedSha256, sha256Of(file))
        return file
    }

    private fun assertDecompresses(sample: Sample) {
        val target = File(workDirectory, sample.name)
        Zstd.decompress(compressed(sample), target)
        assertEquals(sample.bytes, target.length())
        assertEquals(sample.sha256, sha256Of(target))
        target.delete()
    }

    @Test
    fun decomprimeIlVocabolario() = assertDecompresses(vocabulary)

    @Test
    fun decomprimeIlModelloABlocchi() = assertDecompresses(model)

    @Test
    fun unFileTroncatoLanciaIOException() {
        val whole = compressed(vocabulary)
        val truncated = File(workDirectory, "troncato.zst")
        truncated.writeBytes(whole.readBytes().copyOf((whole.length() / 2).toInt()))

        val failure = runCatching { Zstd.decompress(truncated, File(workDirectory, "troncato.out")) }.exceptionOrNull()

        assertTrue(failure is IOException && "troncato" in failure.message.orEmpty())
    }

    @Test
    fun unFileCheNonEZstdLanciaIOException() {
        val notZstd = File(workDirectory, "testo.zst").apply { writeText("questo non e' un file zstd") }

        val failure = runCatching { Zstd.decompress(notZstd, File(workDirectory, "testo.out")) }.exceptionOrNull()

        assertTrue(failure is IOException)
    }

    @Test
    fun unFileVuotoOMancanteLanciaIOException() {
        val empty = File(workDirectory, "vuoto.zst").apply { writeBytes(ByteArray(0)) }

        assertTrue(runCatching { Zstd.decompress(empty, File(workDirectory, "vuoto.out")) }.exceptionOrNull() is IOException)
        assertTrue(
            runCatching { Zstd.decompress(File(workDirectory, "assente.zst"), File(workDirectory, "assente.out")) }.exceptionOrNull() is IOException,
        )
    }

    @Test
    fun unaDestinazioneNonScrivibileLanciaIOException() {
        val failure = runCatching {
            Zstd.decompress(compressed(vocabulary), File(workDirectory, "cartella-inesistente/out.bin"))
        }.exceptionOrNull()

        assertTrue(failure is IOException)
    }

    private companion object {
        const val CDN = "https://firefox-settings-attachments.cdn.mozilla.net/"
    }
}
