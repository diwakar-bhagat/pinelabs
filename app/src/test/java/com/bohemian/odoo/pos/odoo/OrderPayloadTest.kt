package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import com.bohemian.odoo.pos.pine.PayMode
import com.bohemian.odoo.pos.pine.TxnDetails
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OrderPayloadTest {
    private val product = Product(9096, "Makino Corn Munch Cream & Onion", Paise(7000), "123", null, listOf(36))
    private val line = CartLine("l-1", product, 2, totalIncl = Paise(14000), totalExcl = Paise(13332))
    private fun sale(paid: Paise = Paise(14000)) = PaidSale(
        orderUuid = "order-1", sessionId = 246, lines = listOf(line),
        totals = CartTotals(Paise(14000), Paise(13332)), customer = Customer(139, "Sunayana", "+919999999999"),
        payments = listOf(PaymentLineData(21, paid, PayMode.UPI, "E700260921153000123", TxnDetails(rrn = "412", transactionId = "T9", authCode = "OK1"))),
        paidAtMillis = 1_790_000_000_000L,
    )

    @Test fun `payload matches sync_from_ui shape`() {
        val order = OrderSyncService.buildPayload(sale())["orders"]!!.jsonArray.single().jsonObject
        assertEquals("order-1", order["uuid"]!!.jsonPrimitive.content)
        assertEquals("paid", order["state"]!!.jsonPrimitive.content)
        assertEquals(246, order["session_id"]!!.jsonPrimitive.content.toInt())
        assertEquals("140.00", order["amount_total"]!!.jsonPrimitive.content)
        assertEquals("6.68", order["amount_tax"]!!.jsonPrimitive.content)
        val l = order["lines"]!!.jsonArray.single().jsonArray
        assertEquals(0, l[0].jsonPrimitive.content.toInt())
        val vals = l[2].jsonObject
        assertEquals("70.00", vals["price_unit"]!!.jsonPrimitive.content)
        assertEquals("[[6,0,[36]]]", vals["tax_ids"].toString())
        val p = order["payment_ids"]!!.jsonArray.single().jsonArray[2].jsonObject
        assertEquals(21, p["payment_method_id"]!!.jsonPrimitive.content.toInt())
        assertEquals("T9", p["transaction_id"]!!.jsonPrimitive.content)
        assertEquals("E700260921153000123", p["payment_ref_no"]!!.jsonPrimitive.content)
        assertFalse(p.containsKey("card_no")) // UPI: no card digits
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses to sync when paid differs from total`() { OrderSyncService.buildPayload(sale(paid = Paise(13900))) }

    @Test(expected = IllegalArgumentException::class)
    fun `refuses unpriced lines`() {
        OrderSyncService.buildPayload(sale().copy(lines = listOf(line.copy(totalIncl = null))))
    }
}
