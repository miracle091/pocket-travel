package com.pockettravel.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideCategoryTest {

    @Test
    fun `una categoria nota si legge dal nome`() {
        assertEquals(GuideCategory.DA_SAPERE, guideCategoryOrNull("DA_SAPERE"))
        assertEquals(GuideCategory.COSA_VEDERE, guideCategoryOrNull("COSA_VEDERE"))
        assertEquals(GuideCategory.FATTI_RAPIDI, guideCategoryOrNull("FATTI_RAPIDI"))
        assertEquals(GuideCategory.STORIA, guideCategoryOrNull("STORIA"))
        assertEquals(GuideCategory.CLIMA, guideCategoryOrNull("CLIMA"))
    }

    @Test
    fun `una categoria sconosciuta ritorna null invece di lanciare`() {
        assertNull(guideCategoryOrNull("CATEGORIA_FUTURA_SCONOSCIUTA"))
        assertNull(guideCategoryOrNull(""))
    }
}
