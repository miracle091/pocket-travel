package com.pockettravel.feature.map

import btools.codec.DataBuffers
import btools.codec.MicroCache2
import btools.codec.StatCoderContext
import btools.mapaccess.OsmNode
import btools.mapaccess.OsmNodesMap
import com.pockettravel.core.data.Rd5Merger

/**
 * Le strade che escono da una micro-cella di un .rd5, lette con il decoder di BRouter (senza profilo: tutte
 * le strade, anche quelle vietate a un mezzo). Per Rd5Merger, che sposta i bordi fra regioni dove le
 * build combaciano. Non thread-safe (buffer di BRouter riusati): uno per unione.
 */
class BRouterRd5Links : Rd5Merger.Links {
    private val buffers = DataBuffers()

    override fun targets(lonIdx: Int, latIdx: Int, divisor: Int, bytes: ByteArray): Collection<Long> {
        val cache = MicroCache2(StatCoderContext(bytes), buffers, lonIdx, latIdx, divisor, null, null)
        val cellSize = MICRO_DEGREES / divisor
        val nodes = OsmNodesMap()
        val targets = HashSet<Long>()
        for (i in 0 until cache.size) {
            val id = cache.getIdForIndex(i)
            if (!cache.getAndClear(id)) continue
            val node = OsmNode(id)
            node.parseNodeBody(cache, nodes, nodes.byteArrayUnifier)
            var link = node.firstlink
            while (link != null) {
                val target = link.getTarget(node)
                if (!cache.isInternal(target.ilon, target.ilat)) targets += Rd5Merger.cellKey(target.ilon / cellSize, target.ilat / cellSize)
                link = link.getNext(node)
            }
        }
        return targets
    }

    private companion object {
        // Gradi in BRouter: interi in milionesimi.
        const val MICRO_DEGREES = 1_000_000
    }
}
