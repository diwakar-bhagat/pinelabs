package com.bohemian.odoo.pos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class PaiseTest {
    @Test fun `odoo floats convert without float drift`() {
        assertEquals(115L, Paise.fromOdoo(1.15).value)       // naive 1.15*100 = 114.999…
        assertEquals(52800L, Paise.fromOdoo(528.0).value)
        assertEquals(6666L, Paise.fromOdoo(66.66).value)
        assertEquals(1999L, Paise.fromOdoo(19.99).value)
    }

    @Test fun `half up rounding at the boundary`() {
        assertEquals(10L, Paise.fromRupees(BigDecimal("0.095")).value)
        assertEquals(9L, Paise.fromRupees(BigDecimal("0.094")).value)
    }

    @Test fun `formatting`() {
        assertEquals("₹528.00", Paise(52800).format())
        assertEquals("-₹5.00", Paise(-500).format())
        assertEquals("70.00", Paise(7000).toPlainString())
    }

    @Test fun `parse cashier input`() {
        assertEquals(52850L, Paise.parse("528.5")!!.value)
        assertNull(Paise.parse("5.555"))
        assertNull(Paise.parse("abc"))
    }

    @Test fun `arithmetic`() {
        assertEquals(Paise(24000), Paise(12000) * 2)
        assertEquals(Paise(300), listOf(Paise(100), Paise(200)).sum())
    }
}
