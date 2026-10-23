package com.bohemian.odoo.pos.payment

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.odoo.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentSplitTest {
    private val cash = PaymentMethod(2, "Cash", isCash = true, terminal = null)
    private val upi = PaymentMethod(7, "UPI", isCash = false, terminal = null)
    private val e700 = PaymentMethod(22, "Pine Labs E700 (A2A)", isCash = false, terminal = "pine_labs")

    private fun r(rupees: Long) = Paise.ofRupees(rupees)

    @Test fun `new split is due in full`() {
        val s = PaymentSplit(r(75))
        assertEquals(r(75), s.remainingDue)
        assertEquals(Paise.ZERO, s.change)
        assertFalse(s.isComplete)
    }

    @Test fun `first line defaults to the whole due amount`() {
        val s = PaymentSplit(r(75)).add("a", upi).getOrThrow()
        assertEquals(r(75), s.paid)
        assertTrue(s.isComplete)
    }

    @Test fun `split cash and UPI covers the bill`() {
        val s = PaymentSplit(r(200))
            .add("u", upi, r(120)).getOrThrow()
            .add("c", cash).getOrThrow() // defaults to the 80 still due
        assertEquals(r(200), s.paid)
        assertEquals(Paise.ZERO, s.remainingDue)
        assertTrue(s.isComplete)
        assertEquals(Paise.ZERO, s.change)
    }

    @Test fun `non-cash line cannot exceed what is due`() {
        val s = PaymentSplit(r(100)).add("u", upi, r(60)).getOrThrow()
        val err = s.add("e", e700, r(50)) // only 40 due
        assertTrue(err.isFailure)
        assertTrue(err.exceptionOrNull()!!.message!!.contains("higher than the due amount"))
    }

    @Test fun `non-cash can take exactly the due amount`() {
        val s = PaymentSplit(r(100)).add("u", upi, r(60)).getOrThrow().add("e", e700, r(40))
        assertTrue(s.isSuccess)
        assertTrue(s.getOrThrow().isComplete)
    }

    @Test fun `cash can overpay and the excess is change`() {
        val s = PaymentSplit(r(75)).add("c", cash, r(100)).getOrThrow()
        assertEquals(r(25), s.change)
        assertEquals(Paise.ZERO, s.remainingDue)
        assertTrue(s.isComplete)
    }

    @Test fun `change only appears from cash`() {
        // UPI over the due is refused, so no change can be created by a non-cash method.
        val s = PaymentSplit(r(75)).add("u", upi, r(75)).getOrThrow()
        assertTrue(s.setAmount("u", r(80)).isFailure)
        assertEquals(Paise.ZERO, s.change)
    }

    @Test fun `quick plus 50 adds to the selected cash line`() {
        val s = PaymentSplit(r(120)).add("c", cash, r(70)).getOrThrow().quickAdd(50).getOrThrow()
        assertEquals(r(120), s.paid)
        assertEquals(Paise.ZERO, s.change)
        val over = s.quickAdd(10).getOrThrow()
        assertEquals(r(10), over.change)
    }

    @Test fun `quick add refused for non-cash over the due amount`() {
        val s = PaymentSplit(r(100)).add("u", upi, r(90)).getOrThrow()
        assertTrue(s.quickAdd(20).isFailure)
    }

    @Test fun `quick add needs a selected line`() {
        assertTrue(PaymentSplit(r(100)).quickAdd(10).isFailure)
    }

    @Test fun `removing a line restores the due amount`() {
        val s = PaymentSplit(r(100)).add("u", upi, r(60)).getOrThrow().add("c", cash, r(40)).getOrThrow()
        val back = s.remove("c")
        assertEquals(r(40), back.remainingDue)
        assertEquals(1, back.lines.size)
    }

    @Test fun `duplicate uuid is refused`() {
        val s = PaymentSplit(r(100)).add("u", upi, r(10)).getOrThrow()
        assertTrue(s.add("u", cash, r(10)).isFailure)
    }

    @Test fun `negative amount is refused`() {
        assertTrue(PaymentSplit(r(100)).add("c", cash, Paise(-1)).isFailure)
    }
}
