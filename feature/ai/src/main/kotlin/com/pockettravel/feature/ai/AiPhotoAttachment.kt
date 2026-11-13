package com.pockettravel.feature.ai

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.pockettravel.core.ui.AppIcons
import com.pockettravel.core.ui.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Allega una foto": un menu con la galleria (selettore di Android, senza permessi) e la fotocamera. Lo scatto va in
 * `cache/photos` attraverso il FileProvider del modulo e si cancella appena letto (onPhotoChosen). Il permesso della
 * fotocamera si chiede al primo scatto: dichiarato nel manifest, senza permesso l'app della fotocamera non parte.
 */
@Composable
internal fun PhotoAttachButton(actions: AiActions) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    // La cartella si crea allo scatto, non nella composizione; il resolver dell'app, non dell'Activity: la lettura
    // continua nel ViewModel anche dopo una rotazione.
    val capture = remember(context) { File(File(context.cacheDir, "photos"), "camera.jpg") }
    val resolver = context.applicationContext.contentResolver
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) actions.onPhotoChosen({ resolver.openInputStream(uri) }, {})
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) actions.onPhotoChosen({ capture.inputStream() }, { capture.delete() }) else capture.delete()
    }
    val takePicture = {
        capture.parentFile?.mkdirs()
        try {
            camera.launch(FileProvider.getUriForFile(context, modelFileAuthority(context), capture))
        } catch (_: ActivityNotFoundException) {
            // Telefono senza app della fotocamera, o fotocamera bloccata dal profilo di lavoro.
            Toast.makeText(context, R.string.ai_photo_no_camera, Toast.LENGTH_LONG).show()
        }
    }
    // Dopo il secondo rifiuto Android non chiede piu': senza questo avviso il tocco non farebbe niente.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) takePicture() else Toast.makeText(context, R.string.ai_photo_camera_denied, Toast.LENGTH_LONG).show()
    }
    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(AppIcons.Image, contentDescription = stringResource(R.string.ai_photo_attach))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ai_photo_gallery)) },
                leadingIcon = { Icon(AppIcons.Image, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ai_photo_camera)) },
                leadingIcon = { Icon(AppIcons.Camera, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    if (granted) takePicture() else permission.launch(Manifest.permission.CAMERA)
                },
            )
        }
    }
}

// Foto solo online, con un modello che la legge, e non nel foglio del testo selezionato.
private val AiUiState.canAttach: Boolean get() = mode == AiEngineMode.ONLINE && canAttachPhoto && selectedText == null

/** Una foto allegata che partira' con la domanda: basta da sola, con la domanda di ai_photo_question. */
internal val AiUiState.hasPhotoToSend: Boolean get() = canAttach && attachedPhoto != null

/** "Allega una foto" a sinistra del campo della domanda, o null dove una foto non si puo' allegare. */
internal fun photoAttachIcon(uiState: AiUiState, actions: AiActions): (@Composable () -> Unit)? =
    if (uiState.canAttach) ({ PhotoAttachButton(actions) }) else null

/** La foto allegata sopra il campo della domanda, con il pulsante per toglierla. */
@Composable
internal fun AttachedPhotoPreview(uiState: AiUiState, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val photo = uiState.attachedPhoto
    when {
        !uiState.canAttach -> Unit
        uiState.isPreparingPhoto -> Box(modifier = modifier.size(PREVIEW_SIZE), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        photo != null -> Box(modifier = modifier) {
            PhotoThumbnail(photo, size = PREVIEW_SIZE)
            // Su un cerchio pieno: una X sola sparirebbe sulle foto scure.
            FilledTonalIconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.xs).size(28.dp)) {
                Icon(AppIcons.Close, contentDescription = stringResource(R.string.ai_photo_remove), modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** La foto dell'ultima domanda, sopra la sua bolla: le altre non restano nella conversazione. */
@Composable
internal fun AskedPhoto(photo: AttachedPhoto?) {
    if (photo == null) return
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { PhotoThumbnail(photo, size = 160.dp) }
}

/** La foto allegata o mandata con una domanda, come miniatura. */
@Composable
internal fun PhotoThumbnail(photo: AttachedPhoto, size: Dp, modifier: Modifier = Modifier) {
    val sidePx = with(LocalDensity.current) { size.roundToPx() }
    // Fuori dal thread principale e ridotta alla miniatura: la foto intera e' fino a 1568 pixel.
    val bitmap = produceState<ImageBitmap?>(null, photo, sidePx) {
        value = withContext(Dispatchers.Default) { decodeThumbnail(photo.jpeg, sidePx) }
    }.value ?: return
    Image(
        bitmap = bitmap,
        contentDescription = stringResource(R.string.ai_photo_attached),
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(MaterialTheme.shapes.medium),
    )
}

private fun decodeThumbnail(jpeg: ByteArray, sidePx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    // Il lato corto almeno [sidePx]: la miniatura e' quadrata e ritaglia il lato lungo (ContentScale.Crop).
    val shortSide = minOf(bounds.outWidth, bounds.outHeight)
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(shortSide, shortSide, sidePx) }
    return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)?.asImageBitmap()
}

private val PREVIEW_SIZE = 72.dp

/** Avviso alla prima foto online: Invia accende anche "Invia le foto al servizio". */
@Composable
internal fun PhotoConsentDialog(provider: OnlineProvider, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(stringResource(R.string.ai_photo_consent_title, provider.displayName)) },
        text = { Text(stringResource(R.string.ai_photo_consent_text, provider.displayName)) },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(stringResource(R.string.ai_photo_consent_send)) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.ai_photo_consent_cancel)) } },
    )
}
