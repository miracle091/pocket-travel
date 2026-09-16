package com.pockettravel.feature.vault

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs

// Fotocamera interna (CameraX) invece dell'app fotocamera di sistema: serve per disegnare
// l'overlay di inquadratura e il segnale rosso/verde sopra l'anteprima live, cosa impossibile
// delegando a un intent esterno. Resta aperta per piu' scatti di seguito (fino a maxPhotos), si
// chiude solo con la X. Nessuna dipendenza da Google Play Services/ML Kit (scelta esplicita): il
// rilevamento e' un'euristica leggera fatta in casa, vedi isDocumentWellFramed.
@Composable
internal fun DocumentCameraCaptureScreen(
    photoCount: Int,
    maxPhotos: Int,
    onCaptured: (ByteArray) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val imageCapture = remember { ImageCapture.Builder().build() }
        val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
        var isWellFramed by remember { mutableStateOf(false) }
        var isCapturing by remember { mutableStateOf(false) }
        var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
        val canCapture = photoCount < maxPhotos

        DisposableEffect(Unit) {
            onDispose {
                cameraProvider?.unbindAll()
                analysisExecutor.shutdown()
            }
        }

        fun capture() {
            if (isCapturing || !canCapture) return
            isCapturing = true
            val dir = File(context.cacheDir, "camera_tmp").apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            imageCapture.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        stripSensitiveExif(file)
                        onCaptured(file.readBytes())
                        file.delete()
                        isCapturing = false
                    }

                    override fun onError(exception: ImageCaptureException) {
                        file.delete()
                        isCapturing = false
                    }
                },
            )
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(640, 480),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                    ),
                                )
                                .build(),
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        try {
                            isWellFramed = isDocumentWellFramed(imageProxy)
                        } finally {
                            imageProxy.close()
                        }
                    }

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener(
                        {
                            val provider = cameraProviderFuture.get()
                            cameraProvider = provider
                            val preview = Preview.Builder().build()
                                .also { it.surfaceProvider = previewView.surfaceProvider }
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageCapture,
                                analysis,
                            )
                        },
                        ContextCompat.getMainExecutor(ctx),
                    )
                    previewView
                },
            )

            DocumentFrameOverlay(isWellFramed = isWellFramed, modifier = Modifier.fillMaxSize())

            Text(
                text = if (isWellFramed) {
                    "Documento inquadrato bene"
                } else {
                    "Inquadra il documento nel riquadro, ben illuminato e senza riflessi"
                },
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp, start = 24.dp, end = 24.dp),
            )

            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Chiudi fotocamera", tint = Color.White)
            }

            Text(
                text = "$photoCount/$maxPhotos",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            )

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(32.dp)
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(if (canCapture && !isCapturing) Color.White else Color.Gray)
                    .clickable(enabled = canCapture && !isCapturing) { capture() },
            )
        }
    }
}

// Riquadro guida con angoli, colorato in base al segnale di inquadratura (vedi
// isDocumentWellFramed): stessa proporzione centrale (70% larghezza/altezza) usata li' per
// campionare, cosi' il colore corrisponde approssimativamente a cio' che viene analizzato.
@Composable
private fun DocumentFrameOverlay(isWellFramed: Boolean, modifier: Modifier = Modifier) {
    val color = if (isWellFramed) Color(0xFF4CAF50) else Color(0xFFF44336)
    Canvas(modifier = modifier) {
        val frameWidth = size.width * 0.7f
        val frameHeight = size.height * 0.7f
        val left = (size.width - frameWidth) / 2f
        val top = (size.height - frameHeight) / 2f
        val cornerLength = 28.dp.toPx()
        val strokeWidth = 5.dp.toPx()

        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(color, Offset(x, y), Offset(x + dx, y), strokeWidth)
            drawLine(color, Offset(x, y), Offset(x, y + dy), strokeWidth)
        }
        corner(left, top, cornerLength, cornerLength)
        corner(left + frameWidth, top, -cornerLength, cornerLength)
        corner(left, top + frameHeight, cornerLength, -cornerLength)
        corner(left + frameWidth, top + frameHeight, -cornerLength, -cornerLength)
    }
}

private const val SAMPLE_STEP = 6
private const val MIN_BRIGHTNESS = 40
private const val MAX_BRIGHTNESS = 230
private const val EDGE_SCORE_THRESHOLD = 12.0

// Euristica leggera, non un vero rilevamento del documento: nessun modello ML, nessun OpenCV,
// nessuna dipendenza da Google Play Services (scelta esplicita per restare offline/senza Play
// Services). Campiona il piano Y (luminanza, YUV_420_888) nel 70% centrale dell'immagine di
// analisi e combina due segnali: luminosita' media in un range accettabile (ne' troppo buio ne'
// bruciato), e una densita' di bordi (differenze di luminanza tra pixel campionati adiacenti)
// sopra soglia, come proxy di "c'e' qualcosa con bordi/testo nel riquadro" contro uno sfondo
// uniforme (tavolo, muro). E' un suggerimento per l'utente (guida l'overlay rosso/verde), non un
// gate: lo scatto resta sempre possibile. Soglie tarate empiricamente, non garantiscono di
// distinguere un vero documento da un altro oggetto con bordi/texture nel riquadro.
private fun isDocumentWellFramed(image: ImageProxy): Boolean {
    val plane = image.planes.getOrNull(0) ?: return false
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val width = image.width
    val height = image.height

    val regionLeft = (width * 0.15f).toInt()
    val regionRight = (width * 0.85f).toInt()
    val regionTop = (height * 0.15f).toInt()
    val regionBottom = (height * 0.85f).toInt()

    var brightnessSum = 0L
    var edgeSum = 0L
    var sampleCount = 0

    var y = regionTop
    while (y < regionBottom) {
        var previous: Int? = null
        var x = regionLeft
        while (x < regionRight) {
            val index = y * rowStride + x * pixelStride
            if (index in 0 until buffer.capacity()) {
                val luminance = buffer.get(index).toInt() and 0xFF
                brightnessSum += luminance
                sampleCount++
                previous?.let { edgeSum += abs(luminance - it) }
                previous = luminance
            }
            x += SAMPLE_STEP
        }
        y += SAMPLE_STEP
    }

    if (sampleCount == 0) return false
    val averageBrightness = brightnessSum / sampleCount
    val averageEdgeScore = edgeSum.toDouble() / sampleCount

    return averageBrightness in MIN_BRIGHTNESS..MAX_BRIGHTNESS && averageEdgeScore >= EDGE_SCORE_THRESHOLD
}

private val EXIF_TAGS_TO_STRIP = listOf(
    ExifInterface.TAG_GPS_LATITUDE,
    ExifInterface.TAG_GPS_LATITUDE_REF,
    ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE,
    ExifInterface.TAG_GPS_ALTITUDE_REF,
    ExifInterface.TAG_GPS_TIMESTAMP,
    ExifInterface.TAG_GPS_DATESTAMP,
    ExifInterface.TAG_GPS_PROCESSING_METHOD,
    ExifInterface.TAG_DATETIME,
    ExifInterface.TAG_DATETIME_DIGITIZED,
    ExifInterface.TAG_DATETIME_ORIGINAL,
    ExifInterface.TAG_MAKE,
    ExifInterface.TAG_MODEL,
    ExifInterface.TAG_SOFTWARE,
)

private fun stripSensitiveExif(file: File) {
    val exif = ExifInterface(file.absolutePath)
    EXIF_TAGS_TO_STRIP.forEach { exif.setAttribute(it, null) }
    exif.saveAttributes()
}
