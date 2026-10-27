package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * Fixtures: the live ZZ Cash rules (loyalty.rule of program 4, read 2026-10-27) for three product
 * categories, and the carts of the web POS screen recording of the same day, whose points row read
 * +1.2, +65.52, +142.38, +183.34 and +401.44.
 */
class LoyaltyPointsTest {
    private val liveMenu = 1; private val fragranceA = 2; private val fragranceB = 3; private val clothing = 4

    private fun rule(id: Int, amount: String, min: Long, products: Set<Int>) = LoyaltyRule(
        id = id, mode = "auto", pointMode = "money", pointAmount = BigDecimal(amount),
        minimumAmount = Paise.ofRupees(min), minimumTaxIncluded = true, minimumQty = 1,
        anyProduct = false, validProductIds = products,
    )

    private val rules = listOf(
        rule(34, "0.06", 0, setOf(liveMenu)), rule(35, "0.015", 150, setOf(liveMenu)), rule(36, "0.015", 500, setOf(liveMenu)),
        rule(14, "0.05", 0, setOf(fragranceA, fragranceB)), rule(44, "0.0125", 150, setOf(fragranceA, fragranceB)),
        rule(45, "0.0125", 500, setOf(fragranceA, fragranceB)),
        rule(22, "0.05", 0, setOf(clothing)), rule(56, "0.0125", 150, setOf(clothing)), rule(57, "0.0125", 500, setOf(clothing)),
    )

    private fun line(productId: Int, rupees: Long, qty: Int = 1) = CartLine(
        uuid = "l$productId-$rupees", product = Product(productId, "p$productId", Paise.ofRupees(rupees), null, null, listOf(1)),
        qty = qty, totalIncl = Paise.ofRupees(rupees), totalExcl = Paise.ofRupees(rupees),
    )

    @Test fun `single chai earns the base rate only`() =
        assertEquals(Paise(120), LoyaltyPoints.earned(rules, listOf(line(liveMenu, 20))))

    @Test fun `tiers add up above 150 and 500`() {
        assertEquals(Paise(6552), LoyaltyPoints.earned(rules, listOf(line(liveMenu, 728))))
        assertEquals(Paise(14238), LoyaltyPoints.earned(rules, listOf(line(liveMenu, 1582))))
    }

    @Test fun `each rule rounds half up like the web POS`() =
        // 0.015 × 2037 = 30.555 → 30.56 twice: 122.22 + 30.56 + 30.56
        assertEquals(Paise(18334), LoyaltyPoints.earned(rules, listOf(line(liveMenu, 2037))))

    @Test fun `mixed cart from the recording earns 401_44`() {
        val cart = listOf(line(liveMenu, 2037), line(fragranceA, 854), line(clothing, 1200), line(fragranceB, 854))
        assertEquals(Paise(40144), LoyaltyPoints.earned(rules, cart))
    }

    @Test fun `unpriced lines and products outside every rule earn nothing`() {
        val unpriced = line(liveMenu, 20).copy(totalIncl = null, totalExcl = null)
        assertEquals(Paise.ZERO, LoyaltyPoints.earned(rules, listOf(unpriced, line(99, 500))))
    }

    @Test fun `coupon data has the web POS shape with a reward`() {
        val data = LoyaltyPoints.couponData(4, partnerId = 83, cardId = 535, earned = Paise(40144),
            spent = Paise(40000), rewardCodes = listOf("uvjen744u"))!!
        val e = data["535"]!!.jsonObject
        assertEquals("1.44", e["points"]!!.jsonPrimitive.content)
        assertEquals("401.44", e["points_earned"]!!.jsonPrimitive.content)
        assertEquals("400.00", e["points_spent"]!!.jsonPrimitive.content)
        assertEquals(535, e["coupon_id"]!!.jsonPrimitive.int)
        assertEquals(4, e["program_id"]!!.jsonPrimitive.int)
        assertEquals(83, e["partner_id"]!!.jsonPrimitive.int)
        assertEquals("false", e["barcode"]!!.jsonPrimitive.content)
        assertEquals("uvjen744u", e["line_codes"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test fun `customer without a card gets a negative id and no anonymous points`() {
        val data = LoyaltyPoints.couponData(4, 83, null, Paise(120), Paise.ZERO, emptyList())!!
        assertEquals(LoyaltyPoints.NEW_CARD_ID, data["-1"]!!.jsonObject["coupon_id"]!!.jsonPrimitive.int)
        assertNull(LoyaltyPoints.couponData(4, null, null, Paise(120), Paise.ZERO, emptyList()))
        assertNull(LoyaltyPoints.couponData(4, 83, 535, Paise.ZERO, Paise.ZERO, emptyList()))
    }
}
