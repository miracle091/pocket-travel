package com.pockettravel.pipeline

import org.json.JSONObject

/**
 * La pulizia del wikitext delle guide dell'app (cleanBody) per gli script Python del dataset (generate_sft_dataset.py):
 * una sezione grezza per riga su stdin, {"raw": "..."}, e il testo pulito su stdout, {"text": "..."}, nello stesso
 * ordine. Cosi' il modello vede nel training lo stesso testo (sottotitoli "▸", elenchi "•", a capo) che l'app gli
 * mette nel contesto. Il comando per lanciarlo lo stampa il task cleanWikitextCommand.
 */
fun main() {
    val out = System.out.bufferedWriter(Charsets.UTF_8)
    System.`in`.bufferedReader(Charsets.UTF_8).lineSequence().forEach { line ->
        out.write(JSONObject().put("text", cleanBody(JSONObject(line).getString("raw"))).toString())
        out.newLine()
        out.flush()
    }
}
