package com.bohemian.odoo.pos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingRefTest {
    @Test fun `format is alnum 19 chars with config prefix`() {
        val ref = BillingRefGenerator("E700 UAT", clock = { 1_790_000_000_000L }).next()
        assertEquals(19, ref.length)
        assertTrue(ref.startsWith("E700"))
        assertTrue(ref.all { it.isLetterOrDigit() })
    }

    @Test fun `never repeats back to back even with frozen clock`() {
        val g = BillingRefGenerator("KLJ", clock = { 1_790_000_000_000L })
        val refs = (1..200).map { g.next() }
        refs.zipWithNext().forEach { (a, b) -> assertTrue(a != b) }
        assertTrue(refs.first().startsWith("KLJX"))
    }
}
