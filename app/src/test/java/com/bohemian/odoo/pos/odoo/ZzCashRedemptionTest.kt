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

    private fun line(uuid: String, rupees: Long, taxIds: List<Int>) = CartLine(
        uuid, Product(uuid.hashCode(), uuid, Paise.ofRupees(rupees), null, null, taxIds), 1,
        totalIncl = Paise.ofRupees(rupees), totalExcl = Paise.ofRupees(rupees),
    )

    @Test fun `one tax rate gives one reward line`() {
        val parts = ZzCashRules.split(Paise.ofRupees(50), listOf(line("a", 125, listOf(39)), line("b", 75, listOf(39))))
        assertEquals(listOf(RewardPart(listOf(39), Paise.ofRupees(50))), parts)
    }

    @Test fun `mixed rates split like the recorded web POS sale`() {
        // ₹4,945 cart: ₹2,037 of 5% food, ₹2,908 of 18% retail. Odoo showed −164.77 and −235.23.
        val cart = listOf(line("food", 2037, listOf(5)), line("scent", 854, listOf(18)),
            line("tee", 1200, listOf(18)), line("scent2", 854, listOf(18)))
        val parts = ZzCashRules.split(Paise.ofRupees(400), cart)
        assertEquals(listOf(RewardPart(listOf(5), Paise(16477)), RewardPart(listOf(18), Paise(23523))), parts)
    }

    @Test fun `parts always add up to the reward and tax order does not matter`() {
        val cart = listOf(line("a", 100, listOf(39, 36)), line("b", 100, listOf(36, 39)), line("c", 100, listOf(7)))
        val parts = ZzCashRules.split(Paise.ofRupees(100), cart)
        assertEquals(2, parts.size)
        assertEquals(Paise.ofRupees(100), parts.fold(Paise.ZERO) { a, p -> a + p.amount })
        assertEquals(listOf(36, 39), parts[0].taxIds)
    }

    @Test fun `unpriced or empty cart cannot be split`() {
        assertTrue(ZzCashRules.split(Paise.ofRupees(50), emptyList()).isEmpty())
        assertTrue(ZzCashRules.split(Paise.ofRupees(50), listOf(line("a", 100, listOf(5)).copy(totalIncl = null))).isEmpty())
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
