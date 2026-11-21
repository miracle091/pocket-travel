package com.pockettravel.feature.ai

/**
 * Un file della coppia di modelli di Firefox Translations (modello, vocabolario, lista di parole per la traduzione):
 * [fileName] e' il nome dopo la decompressione, [attachment] il percorso del file `.zst` sulla CDN di Firefox. Le
 * impronte vengono dal registro di Firefox (collezione Remote Settings `translations-models-v2`): [compressedSha256]
 * e' quella del `.zst` (attachment.hash), [sha256] quella del file decompresso (decompressedHash).
 */
internal data class BergamotFile(
    val fileName: String,
    val attachment: String,
    val compressedBytes: Long,
    val compressedSha256: String,
    val bytes: Long,
    val sha256: String,
)

/**
 * La coppia italiano-inglese (versione 3.0, variante base-memory), fissata qui e non letta dal registro all'avvio:
 * un modello nuovo entra con un rilascio dell'app, dopo averlo provato sul telefono.
 */
object BergamotModels {
    const val BASE_URL = "https://firefox-settings-attachments.cdn.mozilla.net/"

    /** Il vocabolario per le due direzioni: i due file di Firefox sono identici, se ne tiene uno solo. */
    const val VOCABULARY_FILE = "vocab.enit.spm"
    const val ENGLISH_TO_ITALIAN_MODEL = "model.enit.intgemm.alphas.bin"
    const val ENGLISH_TO_ITALIAN_SHORTLIST = "lex.50.50.enit.s2t.bin"
    const val ITALIAN_TO_ENGLISH_MODEL = "model.iten.intgemm.alphas.bin"
    const val ITALIAN_TO_ENGLISH_SHORTLIST = "lex.50.50.iten.s2t.bin"

    /** I sei record del registro, come pubblicati da Firefox. */
    internal val RECORDS: List<BergamotFile> = listOf(
        BergamotFile(
            fileName = "lex.50.50.enit.s2t.bin",
            attachment = "main-workspace/translations-models-v2/598d567f-4a7e-4ae8-b9b2-0c4edbc01aa6.zst",
            compressedBytes = 1_646_871,
            compressedSha256 = "a97b81e39392c07084f3e8340f119e31ae10529634dd0ad6bad1f945d94bb4c7",
            bytes = 4_133_192,
            sha256 = "8b21914804625b2777dae7fdb636eb78f02a3eb8b7bceaa50f29ac740961da93",
        ),
        BergamotFile(
            fileName = "model.enit.intgemm.alphas.bin",
            attachment = "main-workspace/translations-models-v2/3869680e-cedc-4af2-9167-bee368caa4be.zst",
            compressedBytes = 23_600_729,
            compressedSha256 = "ed0580da44b91afd665956959d893c02e16e27240542799a524a3041c523ca17",
            bytes = 31_561_787,
            sha256 = "248f47568788ecc351da7e5e07064d4153b4f71e011364ae2c931ffeec4d1cc2",
        ),
        BergamotFile(
            fileName = "vocab.enit.spm",
            attachment = "main-workspace/translations-models-v2/6779446e-354e-44ec-a0d1-f3435c340b86.zst",
            compressedBytes = 346_942,
            compressedSha256 = "ad75fd13305e6dc6ec6cc95e43fbb3cb2fe8b7ea60332f195785541325555db8",
            bytes = 812_724,
            sha256 = "3ef0211d4ae6db21440892f180f2019fe2bfc110a330ffa9d2eca9665e4f2bc5",
        ),
        BergamotFile(
            fileName = "lex.50.50.iten.s2t.bin",
            attachment = "main-workspace/translations-models-v2/6a146710-1ffc-415e-995e-18588e56f40d.zst",
            compressedBytes = 1_878_098,
            compressedSha256 = "b5855a724527cb0a41dbe0b83de9682a389dab2300904ab84c2a12f4e9056c2e",
            bytes = 4_713_616,
            sha256 = "0700d6d70b30490e68ff9deca0b45b80310745b491855bde3a13e1692d0cbce1",
        ),
        BergamotFile(
            fileName = "model.iten.intgemm.alphas.bin",
            attachment = "main-workspace/translations-models-v2/24527d9a-7ab8-40e3-a240-f894e0244371.zst",
            compressedBytes = 22_858_815,
            compressedSha256 = "5d3d5a8c463cd9f3a35d99d72165281f66c4637d5297c15418fe1e7cda49856a",
            bytes = 31_561_787,
            sha256 = "21b70978ce2f3b4da7a06b5de86a09abe3acd30b9eee1b2ebb3582b9bad790bf",
        ),
        BergamotFile(
            fileName = "vocab.iten.spm",
            attachment = "main-workspace/translations-models-v2/352a4445-9c4b-43df-a292-c76cb040f428.zst",
            compressedBytes = 346_942,
            compressedSha256 = "ad75fd13305e6dc6ec6cc95e43fbb3cb2fe8b7ea60332f195785541325555db8",
            bytes = 812_724,
            sha256 = "3ef0211d4ae6db21440892f180f2019fe2bfc110a330ffa9d2eca9665e4f2bc5",
        ),
    )

    /** I file da tenere sul dispositivo: il vocabolario uguale nelle due direzioni una volta sola (il primo, `vocab.enit.spm`). */
    internal val FILES: List<BergamotFile> = RECORDS.distinctBy { it.sha256 }

    /** Byte da scaricare, compressi. */
    val downloadBytes: Long = FILES.sumOf { it.compressedBytes }

    /** Byte occupati sul dispositivo dopo la decompressione. */
    val installedBytes: Long = FILES.sumOf { it.bytes }
}
