package com.pockettravel.feature.ai.testing

import com.pockettravel.feature.ai.BergamotModelManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Gestore dei modelli di traduzione in memoria per i test JVM: nessun file: [sizeBytes] (valida a file installati) e lo stato si impostano a mano. */
class FakeBergamotModelManager(var sizeBytes: Long = 0L) : BergamotModelManager {
    private val _installed = MutableStateFlow(false)
    override val installed: StateFlow<Boolean> = _installed.asStateFlow()

    var deleted = 0

    fun setInstalled(value: Boolean) {
        _installed.value = value
    }

    override fun file(name: String) = File("fake-models/translate", name)

    override fun sizeOnDisk() = if (_installed.value) sizeBytes else 0L

    override suspend fun download(onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit) = setInstalled(true)

    override suspend fun delete() {
        deleted++
        setInstalled(false)
    }
}
