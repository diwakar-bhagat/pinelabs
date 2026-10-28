package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.pine.PayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which Odoo methods the till books card, UPI and cash on, from each store's methods as read live on 2026-10-28. */
class PaymentRolesTest {
    private val razorpay = PaymentMethod(1, "Razorpay", isCash = false, terminal = "razorpay", kind = "terminal")
    private val cashCount = PaymentMethod(2, "Cash", isCash = true, terminal = null, type = "cash")
    private val bankCash = PaymentMethod(4, "Cash", isCash = false, terminal = null)
    private val cards = PaymentMethod(5, "Cards", isCash = false, terminal = null)
    private val online = PaymentMethod(6, "Razorpay Online", isCash = false, terminal = null, type = "online")
    private val upi = PaymentMethod(7, "UPI", isCash = false, terminal = null)
    private val pineWeb = PaymentMethod(10, "Pine labs", isCash = false, terminal = "pine_labs", kind = "terminal")
    private val pineKlj = PaymentMethod(20, "Pine Labs KLJ", isCash = false, terminal = "pine_labs", kind = "terminal")
    private val cafeWeb = PaymentMethod(21, "Razorpay (ZZ Café web)", isCash = false, terminal = null)
    private val e700 = PaymentMethod(22, "Pine Labs E700 (A2A)", isCash = false, terminal = null)
    private val customerAccount = PaymentMethod(13, "Customer Account", isCash = false, terminal = null, type = "pay_later")

    @Test fun `ZenZebra books card and UPI on its E700 method and cash on its cash-count method`() {
        assertEquals(PaymentRoles(card = 22, upi = 22, cash = 2), PaymentRoles.defaults(listOf(razorpay, cashCount, cards, online, upi, pineWeb, e700)))
    }

    @Test fun `KLJ, with no E700 method and a Bank-type Cash, books on its own Cards, UPI and Cash`() {
        assertEquals(PaymentRoles(card = 5, upi = 7, cash = 4), PaymentRoles.defaults(listOf(razorpay, bankCash, cards, online, upi, pineKlj)))
    }

    @Test fun `an E700 method added later wins over Cards and UPI`() {
        assertEquals(PaymentRoles(card = 22, upi = 22, cash = 4), PaymentRoles.defaults(listOf(bankCash, cards, upi, pineKlj, e700)))
    }

    @Test fun `a café POS with only web payments has nothing to book on`() {
        val roles = PaymentRoles.defaults(listOf(online, cafeWeb))
        assertEquals(PaymentRoles(), roles)
        assertFalse(roles.any)
        // Still offered in Setup for a supervisor to choose: a plain bank method.
        assertEquals(listOf(cafeWeb), PaymentRoles.terminalChoices(listOf(online, cafeWeb)))
    }

    @Test fun `Odoo-driven terminals, QR, online and customer accounts are never offered`() {
        val all = listOf(razorpay, cashCount, bankCash, cards, online, upi, pineWeb, pineKlj, e700, customerAccount)
        assertEquals(listOf(5, 7, 22), PaymentRoles.terminalChoices(all).map { it.id })
        assertEquals(listOf(2, 4), PaymentRoles.cashChoices(all).map { it.id })
        assertFalse(PaymentRoles.bookable(PaymentMethod(30, "UPI QR", isCash = false, terminal = null, kind = "qr_code")))
    }

    @Test fun `each terminal mode books on its own method`() {
        val roles = PaymentRoles(card = 5, upi = 7, cash = 4)
        assertEquals(5, roles.forMode(PayMode.CARD))
        assertEquals(7, roles.forMode(PayMode.UPI))
        assertEquals(7, roles.forMode(PayMode.BHARAT_QR))
        assertTrue(roles.any)
        assertEquals(setOf(5, 7, 4), roles.ids)
    }
}
