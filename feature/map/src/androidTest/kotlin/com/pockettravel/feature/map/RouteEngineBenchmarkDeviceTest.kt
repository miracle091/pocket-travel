package com.pockettravel.feature.map

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.feature.map.di.RouteEngineModule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Misura tempo e memoria del calcolo di un percorso sul dispositivo, con segmenti .rd5 veri di
 * brouter.de (troppo grandi per il repo). Saltato se i segmenti mancano. Per eseguirlo: installare
 * l'APK di test (assembleDebugAndroidTest), copiare i segmenti in /data/local/tmp e poi nella
 * cartella privata del pacchetto con "adb shell run-as com.pockettravel.feature.map.test cp ...
 * files/rd5-bench/...", lanciare con "adb shell am instrument -w -e class <questa classe>
 * com.pockettravel.feature.map.test/androidx.test.runner.AndroidJUnitRunner". Sottocartelle:
 * - sanmarino/E10_N40.rd5: ritagliato su San Marino (tools/data-pipeline/scripts/clip_rd5.py);
 * - italia/: E5_N40, E5_N45, E10_N40, E10_N45 interi.
 * I risultati vanno nel log (tag RouteBenchmark).
 */
@RunWith(AndroidJUnit4::class)
class RouteEngineBenchmarkDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val benchDir: File? = File(context.filesDir, "rd5-bench")

    @Test
    fun corto_sanMarinoAPiedi() = measure("sanmarino", "trekking", "San Marino a piedi", RoutePoint(43.9690, 12.4800), RoutePoint(43.9356, 12.4473))

    @Test
    fun medio_romaAPiedi() = measure("italia", "trekking", "Roma Termini -> Vaticano a piedi", RoutePoint(41.9009, 12.5010), RoutePoint(41.9022, 12.4539))

    @Test
    fun lungo_milanoRomaInAuto() = measure("italia", "car-vario", "Milano -> Roma in auto", RoutePoint(45.4642, 9.1900), RoutePoint(41.9009, 12.5010))

    private fun measure(dirName: String, profile: String, label: String, from: RoutePoint, to: RoutePoint) {
        val segments = benchDir?.let { File(it, dirName) }
        assumeTrue("segmenti mancanti in $segments", segments?.listFiles { f -> f.extension == "rd5" }?.isNotEmpty() == true)
        // Stessi profili dell'app: creare la factory li copia dagli asset, come in produzione.
        val profileDir = File(context.filesDir, "brouter-profile")
        RouteEngineModule.provideRouteEngineFactory(File(context.cacheDir, "unused"), context, UsageModePreferences(context, MapFilterPreferences(context)))
        val engine = BRouterRouteEngine(segments!!, profileDir, profile, maxRunningTimeMillis = 300_000)

        repeat(2) { attempt ->
            System.gc()
            val runtime = Runtime.getRuntime()
            val javaBefore = runtime.totalMemory() - runtime.freeMemory()
            val nativeBefore = Debug.getNativeHeapAllocatedSize()
            var javaPeak = javaBefore
            var nativePeak = nativeBefore
            val running = AtomicBoolean(true)
            val sampler = thread {
                while (running.get()) {
                    javaPeak = maxOf(javaPeak, runtime.totalMemory() - runtime.freeMemory())
                    nativePeak = maxOf(nativePeak, Debug.getNativeHeapAllocatedSize())
                    Thread.sleep(20)
                }
            }
            val start = System.nanoTime()
            // Stima dell'avanzamento nel tempo: per tarare i pesi delle passate (RoutingEngine.getProgress).
            val samples = java.util.Collections.synchronizedList(mutableListOf<Pair<Long, Double>>())
            val result = runBlocking { engine.route(from, to) { p -> samples += (System.nanoTime() - start) / 1_000_000 to p } }
            val millis = (System.nanoTime() - start) / 1_000_000
            if (attempt == 0 && millis > 2_000) {
                val timeline = (1..9).joinToString(" ") { tenth ->
                    val at = millis * tenth / 10
                    val p = samples.lastOrNull { it.first <= at }?.second ?: 0.0
                    "${tenth * 10}%t=${(p * 100).toInt()}%"
                }
                Log.i(TAG, "$label avanzamento stimato per tempo trascorso: $timeline")
            }
            running.set(false)
            sampler.join()
            val route = (result as? RouteResult.Found)?.route
            Log.i(
                TAG,
                "$label [${if (attempt == 0) "primo" else "secondo"}]: ${millis} ms, " +
                    "${route?.let { "%.1f km, %d svolte".format(it.distanceMeters / 1000, it.instructions.size) } ?: result}, " +
                    "heap Java +${(javaPeak - javaBefore) / MB} MB (max ${runtime.maxMemory() / MB} MB), nativo +${(nativePeak - nativeBefore) / MB} MB",
            )
            assertTrue("$label: $result", route != null)
        }
    }

    private companion object {
        const val TAG = "RouteBenchmark"
        const val MB = 1024 * 1024
    }
}
