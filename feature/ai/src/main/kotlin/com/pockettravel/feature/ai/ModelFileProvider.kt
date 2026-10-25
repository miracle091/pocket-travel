package com.pockettravel.feature.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

/** FileProvider dei modelli: una classe propria evita conflitti con altri provider dell'app nel manifest unito. */
class ModelFileProvider : FileProvider()

internal fun modelFileAuthority(context: Context): String = "${context.packageName}.modelfiles"

/** Apre il foglio di condivisione di Android con il GGUF di [file] ("Invia a un altro telefono"). */
internal fun shareModelFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, modelFileAuthority(context), file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.ai_model_send)))
}

/** Nome e dimensione (0 se ignota) di un documento scelto con il selettore. */
internal fun queryDocument(context: Context, uri: Uri): Pair<String?, Long> {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(cursor::getString)
            val size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong) ?: 0L
            return name to size
        }
    }
    return null to 0L
}
