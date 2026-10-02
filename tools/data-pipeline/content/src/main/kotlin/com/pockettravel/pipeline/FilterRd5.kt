package com.pockettravel.pipeline

import btools.codec.Rd5CarFilter
import java.io.File
import kotlin.system.exitProcess

/**
 * Variante "solo auto" di segmenti BRouter .rd5 gia' ritagliati (clip_rd5.py): per ogni file in input scrive in
 * <outDir> un file con lo stesso nome e le sole vie che un'auto puo' usare (vedi Rd5CarFilter).
 *
 * Uso: filterRd5 <lookups.dat> <outDir> <file.rd5>...
 */
fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("Uso: filterRd5 <lookups.dat> <outDir> <file.rd5>...")
        exitProcess(2)
    }
    val lookups = File(args[0])
    val outDir = File(args[1]).apply { mkdirs() }
    args.drop(2).map(::File).forEach { input ->
        val output = File(outDir, input.name)
        require(output.canonicalPath != input.canonicalPath) { "L'uscita sovrascriverebbe ${input.name}" }
        Rd5CarFilter.rewrite(lookups, input, output, true)
        println("${input.name}: ${input.length()} -> ${output.length()} byte (${output.length() * 100 / maxOf(input.length(), 1)}%)")
    }
}
