package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZzCashRedemptionTest {

    private fun reward(id: Int, rupees: Long, productId: Int = 29102) =
        ZzCashReward(id, "₹$rupees off ($rupees ZZ Cash)", Paise.ofRupees(rupees), Paise.ofRupees(rupees), productId)

    private val balance = ZzCashBalance(partnerId = 19, cardId = 535, points = Paise(6700))

    // ---- eligibility -------------------------------------------------------------

    @Test fun `card with 67 rupees can pay 50 but not 100`() {
        val rewards = listOf(reward(6, 50), reward(7, 100))
        val got = ZzCashRules.eligible(balance, rewards, cartTotal = Paise.ofRupees(125))
        assertEquals(listOf(6), got.map { it.rewardId })
    }

    @Test fun `reward must stay below the bill`() {
        // ₹50 off a ₹50 bill would be a zero-value sale: not offered.
        assertTrue(ZzCashRules.eligible(balance, listOf(reward(6, 50)), cartTotal = Paise.ofRupees(50)).isEmpty())
        assertEquals(1, ZzCashRules.eligible(balance, listOf(reward(6, 50)), cartTotal = Paise(5001)).size)
    }

    @Test fun `partial rupees on the card do not count`() {
        val b = ZzCashBalance(19, 535, Paise(4999)) // ₹49.99 → only ₹49 redeemable
        assertTrue(ZzCashRules.eligible(b, listOf(reward(6, 50)), Paise.ofRupees(200)).isEmpty())
    }

    // ---- tax ---------------------------------------------------------------------

    @Test fun `one tax rate across the cart is accepted`() {
        assertEquals(listOf(39), ZzCashRules.singleTaxSet(listOf(listOf(39), listOf(39))))
    }

    @Test fun `tax order does not matter`() {
        assertEquals(listOf(36, 39), ZzCashRules.singleTaxSet(listOf(listOf(39, 36), listOf(36, 39))))
    }

    @Test fun `mixed tax rates are refused`() {
        assertNull(ZzCashRules.singleTaxSet(listOf(listOf(39), listOf(36))))
    }

    @Test fun `empty cart has no tax set`() {
        assertNull(ZzCashRules.singleTaxSet(emptyList()))
    }

    // ---- reward code -------------------------------------------------------------

    @Test fun `reward code is 9 lowercase alphanumerics`() {
        repeat(200) {
            val code = ZzCashRules.newRewardCode()
            assertEquals(9, code.length)
            assertTrue(code.all { it in 'a'..'z' || it in '0'..'9' })
        }
    }

    @Test fun `reward codes differ between redemptions`() {
        val codes = (1..50).map { ZzCashRules.newRewardCode() }.toSet()
        assertNotEquals(1, codes.size)
    }
}
