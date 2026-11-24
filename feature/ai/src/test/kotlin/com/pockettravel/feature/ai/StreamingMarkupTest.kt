package com.pockettravel.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingMarkupTest {

    @Test
    fun linkSenzaChiusuraSiTaglia() {
        assertEquals("vedi ", withoutPartialMarkup("vedi [Mus"))
        assertEquals("vedi ", withoutPartialMarkup("vedi [Museo]"))
        assertEquals("vedi ", withoutPartialMarkup("vedi [Museo](https://www.vis"))
        assertEquals("", withoutPartialMarkup("[Museo](https://x.it/a"))
    }

    @Test
    fun linkCompletoResta() {
        assertEquals("[Museo](https://x.it/a)", withoutPartialMarkup("[Museo](https://x.it/a)"))
        assertEquals("[Museo](https://x.it/a) e ", withoutPartialMarkup("[Museo](https://x.it/a) e [Ro"))
        assertEquals("vedi [1] qui", withoutPartialMarkup("vedi [1] qui"))
    }

    @Test
    fun soloLUltimaRigaConta() {
        assertEquals("[Mu\ncosa vedere ", withoutPartialMarkup("[Mu\ncosa vedere [Ro"))
    }

    @Test
    fun indirizzoNudoNonFinitoSiTaglia() {
        assertEquals("sito: ", withoutPartialMarkup("sito: https://www.vis"))
        assertEquals("sito: https://x.it ok", withoutPartialMarkup("sito: https://x.it ok"))
    }

    @Test
    fun enfasiAperta() {
        assertEquals("Roma", withoutPartialMarkup("**Roma"))
        assertEquals("ciao Roma e", withoutPartialMarkup("ciao **Roma e"))
        assertEquals("ciao Roma", withoutPartialMarkup("ciao __Roma"))
        assertEquals("ciao Roma", withoutPartialMarkup("ciao *Roma"))
        assertEquals("* Roma", withoutPartialMarkup("* **Roma"))
    }

    @Test
    fun enfasiChiusaOSenzaApertura() {
        assertEquals("ciao **Roma** e", withoutPartialMarkup("ciao **Roma** e"))
        assertEquals("ciao *Roma* e", withoutPartialMarkup("ciao *Roma* e"))
        assertEquals("5 * 3 = 15 e a*b", withoutPartialMarkup("5 * 3 = 15 e a*b"))
        assertEquals("* elemento", withoutPartialMarkup("* elemento"))
        assertEquals("usa snake_case e __init__", withoutPartialMarkup("usa snake_case e __init__"))
    }

    @Test
    fun testoSenzaMarkupResta() {
        assertEquals("Roma e' bella.", withoutPartialMarkup("Roma e' bella."))
        assertEquals("", withoutPartialMarkup(""))
    }
}
