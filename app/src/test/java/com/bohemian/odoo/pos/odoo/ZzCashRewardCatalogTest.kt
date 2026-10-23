package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZzCashRewardCatalogTest {

    private fun row(id: Int, points: Double, discount: Double, productId: Int? = 29102, desc: String = "₹$discount off") = JsonObject(mapOf(
        "id" to JsonPrimitive(id),
        "description" to JsonPrimitive(desc),
        "required_points" to JsonPrimitive(points),
        "discount" to JsonPrimitive(discount),
        "discount_line_product_id" to (productId?.let { JsonArray(listOf(JsonPrimitive(it), JsonPrimitive("₹x"))) } ?: JsonPrimitive(false)),
    ))

    @Test fun `parses a ₹50 reward from Odoo's row shape`() {
        val r = ZzCashService.fromRewardRows(listOf(row(6, 50.0, 50.0))).single()
        assertEquals(6, r.rewardId)
        assertEquals(Paise.ofRupees(50), r.points)
        assertEquals(Paise.ofRupees(50), r.amount)
        assertEquals(29102, r.discountProductId)
    }

    @Test fun `drops a reward whose cost is not its value`() {
        assertTrue(ZzCashService.fromRewardRows(listOf(row(9, 50.0, 60.0))).isEmpty())
    }

    @Test fun `drops a reward with no discount product`() {
        assertTrue(ZzCashService.fromRewardRows(listOf(row(9, 50.0, 50.0, productId = null))).isEmpty())
    }

    @Test fun `sorts cheapest first`() {
        val got = ZzCashService.fromRewardRows(listOf(row(21, 500.0, 500.0), row(6, 50.0, 50.0), row(7, 100.0, 100.0)))
        assertEquals(listOf(6, 7, 21), got.map { it.rewardId })
    }
}
