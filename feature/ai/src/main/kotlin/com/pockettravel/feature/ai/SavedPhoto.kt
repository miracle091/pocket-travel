package com.pockettravel.feature.ai

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

private const val PHOTO_FILE_KEY = "attached_photo_file"
private const val STALE_PHOTO_MS = 24 * 60 * 60 * 1000L

/**
 * Tiene la foto allegata e non ancora inviata in un file di [dir], col nome nello stato salvato del ViewModel: se Android
 * chiude l'app, la foto torna allegata. Un file e non lo stato salvato perche' una foto di qualche centinaio di kB
 * supererebbe il limite del Bundle. Il file sparisce quando la foto si toglie o parte e, con l'AutoCloseable restituito,
 * quando il ViewModel finisce.
 */
internal fun SavedStateHandle.keepPhoto(scope: CoroutineScope, state: MutableStateFlow<AiUiState>, dir: File, io: CoroutineDispatcher): AutoCloseable {
    val restoring = contains(PHOTO_FILE_KEY)
    val file = File(dir, get<String>(PHOTO_FILE_KEY) ?: "attached-${UUID.randomUUID()}.jpg".also { set(PHOTO_FILE_KEY, it) })
    scope.launch {
        // Riaperta senza stato salvato, l'app lascia il file di prima: si cancella dopo un giorno, non subito, perche'
        // un'altra scheda IA aperta o ripristinata dai Recenti puo' ancora usare il suo.
        if (!restoring) withContext(io) { dir.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - STALE_PHOTO_MS }?.forEach(File::delete) }
        val saved = withContext(io) { photoFile(file) { it.takeIf(File::isFile)?.readBytes() } }
        if (saved != null) state.update { if (it.attachedPhoto == null) it.copy(attachedPhoto = AttachedPhoto(saved)) else it }
        state.map { it.attachedPhoto }.distinctUntilChanged().collect { photo ->
            withContext(io) { photoFile(file) { if (photo == null) it.delete() else it.apply { parentFile?.mkdirs() }.writeBytes(photo.jpeg) } }
        }
    }
    return AutoCloseable { file.delete() }
}

// Un errore del disco costa solo la foto dopo la chiusura dell'app, non la domanda.
private fun <T> photoFile(file: File, use: (File) -> T): T? = try {
    use(file)
} catch (error: IOException) {
    Log.w("AiAssistant", "Foto allegata non salvata o non letta: ${error.message}")
    null
}
