package com.pockettravel.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pockettravel.core.data.crypto.HistoryKeys
import com.pockettravel.core.data.db.RegionDatabase
import com.pockettravel.core.data.testing.InMemorySharedPreferences
import com.pockettravel.core.data.testing.SoftwareHistoryMasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import kotlin.system.measureTimeMillis

/**
 * Misure della cronologia su un database Room vero su file, con conversazioni generate: spazio per conversazione, tempo
 * dell'elenco (una chiave RSA da aprire per conversazione) e della ripresa, tempo di un'operazione della chiave del
 * Keystore (lo sblocco). Servono a scegliere dopo quanti giorni una conversazione si cancella da sola. La chiave della
 * cronologia e' software (la biometria non si prova senza una persona): l'elenco usa comunque RSA software anche nell'app.
 * Risultati nel logcat: adb logcat -s ConversationHistory
 */
@RunWith(AndroidJUnit4::class)
class ConversationHistoryDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "conversation-history-test.db"
    private val db = Room.databaseBuilder(context, RegionDatabase::class.java, dbName).build()

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun spazioETempiConMolteConversazioni() = runBlocking<Unit> {
        val keys = HistoryKeys(InMemorySharedPreferences(), SoftwareHistoryMasterKey())
        val repository = ConversationRepository(db.conversationDao(), keys, Dispatchers.IO).also { it.onWriteError = { error -> throw error } }
        val sizeBefore = databaseBytes()
        var lastId = ""
        repeat(CONVERSATIONS) { index ->
            val handle = repository.start({ SUMMARY.copy(title = "Domanda $index: ${SUMMARY.title}") }, turn(index, 0))
            repeat(TURNS_PER_CONVERSATION - 1) { repository.append(handle, turn(index, it + 1)) }
            lastId = handle.id
        }
        val unlock = repository.prepareUnlock()
        check(unlock is HistoryKeys.Unlock.Ready)
        val reader = keys.completeUnlock(unlock.cipher)
        // Prima lettura: aspetta le scritture in coda e scalda il codice.
        assertEquals(CONVERSATIONS, repository.list(reader).size)
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
        val perConversation = (databaseBytes() - sizeBefore) / CONVERSATIONS

        val listMs = measureTimeMillis { repository.list(reader) }
        val resumeMs = measureTimeMillis { checkNotNull(repository.resume(reader, lastId)) }
        Log.i(TAG, "$CONVERSATIONS conversazioni da $TURNS_PER_CONVERSATION turni: $perConversation byte l'una, elenco $listMs ms, ripresa $resumeMs ms")
        Log.i(TAG, "keystore: un'operazione RSA come lo sblocco ${keystoreDecryptMs()} ms")
    }

    private fun databaseBytes(): Long =
        listOf("", "-wal", "-shm").sumOf { context.getDatabasePath(dbName + it).let { file -> if (file.exists()) file.length() else 0L } }

    // Un'operazione con una chiave RSA del Keystore come quella della cronologia, ma senza biometria.
    private fun keystoreDecryptMs(): Long {
        val alias = "pocket_travel_test_history_measure"
        val oaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
        val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .build(),
            )
        }.generateKeyPair()
        try {
            val wrapped = Cipher.getInstance(RSA_OAEP).apply { init(Cipher.ENCRYPT_MODE, pair.public, oaep) }.doFinal(ByteArray(32))
            val privateKey = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as PrivateKey
            return measureTimeMillis { Cipher.getInstance(RSA_OAEP).apply { init(Cipher.DECRYPT_MODE, privateKey, oaep) }.doFinal(wrapped) }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }

    private fun turn(conversation: Int, index: Int) = ConversationTurn(
        askedQuestion = "Domanda $index della conversazione $conversation: serve il visto per entrare e per quanto tempo?",
        question = "Serve il visto per entrare e per quanto tempo si puo' restare? ($conversation.$index)",
        answer = ANSWER,
        citations = listOf("Lettonia › Entrare", "Lettonia › Documenti", "Riga › Arrivare"),
        modelId = "pt-qwen3.5-0.8b",
        cities = listOf("Riga"),
        askedAt = System.currentTimeMillis(),
    )

    private companion object {
        const val TAG = "ConversationHistory"
        const val CONVERSATIONS = 500
        const val TURNS_PER_CONVERSATION = 6
        const val RSA_OAEP = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        val SUMMARY = ConversationSummary(title = "Serve il visto per entrare?", regionId = "lettonia", regionName = "Lettonia")

        // Una risposta tipica dei modelli addestrati: qualche frase, circa 600 caratteri.
        val ANSWER = "I cittadini dell'Unione Europea possono entrare in Lettonia con la carta d'identita' valida per l'espatrio " +
            "o con il passaporto, senza visto e per un periodo illimitato. Per soggiorni oltre i tre mesi serve la registrazione " +
            "presso l'Ufficio per la cittadinanza e la migrazione. I cittadini di altri paesi possono avere bisogno di un visto " +
            "Schengen, valido fino a 90 giorni in un periodo di 180. Alla frontiera possono essere chiesti il biglietto di ritorno " +
            "e i mezzi di sostentamento. Prima di partire conviene verificare le regole sul sito ufficiale."
    }
}
