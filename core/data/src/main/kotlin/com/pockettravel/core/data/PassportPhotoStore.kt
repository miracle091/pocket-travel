package com.pockettravel.core.data

import java.io.File
import javax.inject.Inject
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PassportPhotosDir

// Solo I/O su file gia' cifrati (vedi PassportRepository.savePhoto/loadPhoto per la cifratura):
// una directory interna dell'app (non accessibile ad altre app), un file per foto. Riceve la
// directory gia' risolta (@PassportPhotosDir) invece di un Context, come RegionStorage/RegionsDir:
// nessuna dipendenza Android diretta, testabile con un File temporaneo in un plain unit test.
class PassportPhotoStore @Inject constructor(@param:PassportPhotosDir private val dir: File) {

    fun write(fileName: String, bytes: ByteArray) {
        File(dir, fileName).writeBytes(bytes)
    }

    fun read(fileName: String): ByteArray? =
        File(dir, fileName).takeIf { it.exists() }?.readBytes()

    fun delete(fileName: String) {
        File(dir, fileName).delete()
    }
}
