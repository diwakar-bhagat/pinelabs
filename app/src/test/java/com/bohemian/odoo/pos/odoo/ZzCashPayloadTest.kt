package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The sale is the one captured from the web POS on 2026-10-23 (Monster ₹125, ZZ Cash ₹50 off,
 * paid ₹75 cash). The reward line here must match that capture field for field.
 */
class ZzCashPayloadTest {

    private val monster = Product(8133, "Monster Energy drink ultra can 350ml", Paise.ofRupees(125), null, null, listOf(39))
    private val taxIds = listOf(39)

    private fun redemption(incl: Paise? = Paise.ofRupees(-50), excl: Paise? = Paise(-3572)) = ZzCashRedemption(
        cardId = 535,
        reward = ZzCashReward(6, "₹50 off (50 ZZ Cash)", Paise.ofRupees(50), Paise.ofRupees(50), 29102),
        rewardCode = "uvjen744u",
        parts = listOf(RewardPart(taxIds, Paise.ofRupees(50), incl, excl)),
    )

    private fun sale(red: ZzCashRedemption? = redemption()) = PaidSale(
        orderUuid = "order-zz-1", sessionId = 369,
        lines = listOf(CartLine("l-1", monster, 1, totalIncl = Paise.ofRupees(125), totalExcl = Paise(8928), appliedTaxIds = taxIds)),
        totals = CartTotals(Paise.ofRupees(75), Paise(5356)),
        customer = Customer(19, "Diwakar", "+917048980055"),
        payments = listOf(PaymentLineData(2, Paise.ofRupees(75), null, null, null)),
        paidAtMillis = 1_790_000_000_000L,
        redemption = red,
    )

    private fun order(s: PaidSale): JsonObject =
        OrderSyncService.buildPayload(s)["orders"]!!.jsonArray.single().jsonObject

    private fun rewardLine(o: JsonObject): JsonObject =
        o["lines"]!!.jsonArray.map { it.jsonArray[2].jsonObject }.single { it["is_reward_line"]?.jsonPrimitive?.content == "true" }

    @Test fun `order total is the bill less the reward`() {
        val o = order(sale())
        assertEquals("75.00", o["amount_total"]!!.jsonPrimitive.content)
        assertEquals("75.00", o["amount_paid"]!!.jsonPrimitive.content)
        assertEquals("21.44", o["amount_tax"]!!.jsonPrimitive.content)
    }

    @Test fun `two lines the product and the negative reward`() {
        val lines = order(sale())["lines"]!!.jsonArray
        assertEquals(2, lines.size)
    }

    @Test fun `reward line matches the captured web POS line`() {
        val r = rewardLine(order(sale()))
        assertEquals("29102", r["product_id"]!!.jsonPrimitive.content)
        assertEquals("-50.00", r["price_unit"]!!.jsonPrimitive.content)
        assertEquals("-50.00", r["price_subtotal_incl"]!!.jsonPrimitive.content)
        assertEquals("-35.72", r["price_subtotal"]!!.jsonPrimitive.content)
        assertEquals("6", r["reward_id"]!!.jsonPrimitive.content)
        assertEquals("535", r["coupon_id"]!!.jsonPrimitive.content)
        assertEquals("50.00", r["points_cost"]!!.jsonPrimitive.content)
        assertEquals("uvjen744u", r["reward_identifier_code"]!!.jsonPrimitive.content)
        assertEquals("true", r["is_reward_line"]!!.jsonPrimitive.content)
        assertEquals("1", r["qty"]!!.jsonPrimitive.content)
    }

    @Test fun `reward line has its own uuid`() {
        val r = rewardLine(order(sale()))
        assertEquals(36, r["uuid"]!!.jsonPrimitive.content.length)
    }

    @Test fun `no redemption means no reward line`() {
        val lines = order(sale(red = null))["lines"]!!.jsonArray
        assertEquals(1, lines.size)
    }

    @Test fun `unpriced reward is refused before sync`() {
        assertThrows(IllegalArgumentException::class.java) { order(sale(redemption(incl = null, excl = null))) }
    }

    @Test fun `every key it sends is in the Odoo contract`() {
        assertEquals(emptyList<String>(), OdooContract.uncovered(OrderSyncService.buildPayload(sale())))
    }
}
