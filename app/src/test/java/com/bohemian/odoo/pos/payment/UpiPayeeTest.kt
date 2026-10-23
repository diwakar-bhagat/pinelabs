package com.bohemian.odoo.pos.payment

import com.bohemian.odoo.pos.core.Paise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpiPayeeTest {
    // Test values only. The real IDs live in device settings, never in the repo.
    private val primary = "shop.primary@examplebank"
    private val fallback = "shop.backup@examplebank"

    @Test fun `primary is used by default`() {
        assertEquals(primary, UpiPayee(primary, fallback).activeVpa())
    }

    @Test fun `fallback is used only when asked`() {
        assertEquals(fallback, UpiPayee(primary, fallback, useFallback = true).activeVpa())
    }

    @Test fun `no fallback configured keeps the primary even if asked`() {
        assertEquals(primary, UpiPayee(primary, null, useFallback = true).activeVpa())
    }

    @Test fun `rejects a malformed VPA`() {
        assertFalse(UpiPayee.isValidVpa("no-at-sign"))
        assertFalse(UpiPayee.isValidVpa("a@b"))
        assertFalse(UpiPayee.isValidVpa("bad space@bank"))
        assertTrue(UpiPayee.isValidVpa("shop.valid@examplebank"))
    }

    @Test fun `uri carries VPA, name, amount, currency and bill reference`() {
        val uri = UpiUri.build(UpiPayee(primary), "ZenZebra", Paise.ofRupees(6), "ZenZebra - 000014")
        assertEquals(
            "upi://pay?pa=shop.primary%40examplebank&pn=ZenZebra&am=6.00&cu=INR&tn=ZenZebra%20-%20000014",
            uri,
        )
    }

    @Test fun `uri uses the fallback VPA when selected`() {
        val uri = UpiUri.build(UpiPayee(primary, fallback, useFallback = true), "ZenZebra", Paise(1250), "B1")
        assertTrue(uri.contains("pa=shop.backup%40examplebank"))
        assertTrue(uri.contains("am=12.50"))
    }

    @Test fun `zero or negative amount is refused`() {
        val payee = UpiPayee(primary)
        try {
            UpiUri.build(payee, "ZenZebra", Paise.ZERO, "B1")
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
