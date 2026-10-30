package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The bill from Odoo's answer to sync_from_ui, so a sale needs no read-back. The reply's shape is
 * read_pos_data's (all order and payment fields, links as plain ids), as a live paid order showed it
 * on 2026-10-30; names and numbers here are made up.
 */
class BillFromReplyTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun bills() = BillService(OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { "k" }))

    private fun reply(partnerId: Any = false, methodId: Int = 22) = Json.parseToJsonElement("""
        {"pos.order": [{"id": 9101, "uuid": "u-1", "name": "SWN - 000243", "pos_reference": "2614-3-000243",
            "date_order": "2026-10-30 06:15:00", "partner_id": $partnerId, "cashier": "Store", "config_id": 3,
            "amount_total": 140.0, "amount_tax": 6.67, "access_token": "tok", "lines": [501, 502], "payment_ids": [601]}],
         "pos.order.line": [
            {"id": 502, "order_id": 9101, "full_product_name": "Cardamom Chai", "qty": 1.0, "price_subtotal_incl": 60.0,
             "discount": 0.0, "tax_ids": [30], "product_id": 8202},
            {"id": 501, "order_id": 9101, "full_product_name": "Cookie Butter Coffee", "qty": 1.0, "price_subtotal_incl": 80.0,
             "discount": 0.0, "tax_ids": [30], "product_id": 8625}],
         "pos.payment": [{"id": 601, "pos_order_id": 9101, "payment_method_id": $methodId, "amount": 140.0}],
         "pos.session": []}
    """.trimIndent()).jsonObject

    private val known = KnownNames("SWN", null, null, taxNames = mapOf(30 to "5% GST S"), methodNames = mapOf(22 to "Pine Labs E700 (A2A)", 4 to "Cash"))

    @Test fun `a walk-in sale's bill comes from the reply with no Odoo call`() = runTest {
        val bill = bills().billFromReply("u-1", reply(), known)!!
        assertEquals("SWN - 000243", bill.name)
        assertEquals("2614-3-000243", bill.posReference)
        assertEquals("SWN", bill.storeName)
        assertEquals(listOf("Cookie Butter Coffee", "Cardamom Chai"), bill.lines.map { it.name }) // Odoo's line order
        assertEquals(listOf("5% GST S"), bill.lines.first().taxNames)
        assertEquals(listOf(OdooBillPayment("Pine Labs E700 (A2A)", Paise.ofRupees(140))), bill.payments)
        assertEquals(Paise.ofRupees(140), bill.amountTotal)
        assertEquals("tok", bill.accessToken)
        assertNull(bill.zzCash)
        assertEquals(0, server.requestCount)
    }

    @Test fun `a customer's bill reads only the ZZ Cash texts`() = runTest {
        server.enqueue(MockResponse().setBody("""[{"id": 9101, "x_zz_show": true, "x_zz_earned_txt": "1.40", "x_zz_used": 0.0,
            "x_zz_used_txt": "0", "x_zz_balance_txt": "36.10", "x_zz_expiry": false}]"""))
        val bill = bills().billFromReply("u-1", reply(partnerId = 83), known.copy(customerName = "A Customer", customerPhone = "+91 90000 00000"))!!
        assertEquals("A Customer", bill.customerName)
        assertEquals("36.10", bill.zzCash!!.balance)
        assertEquals(1, server.requestCount)
    }

    @Test fun `anything the till cannot name sends it back to reading the bill`() = runTest {
        assertNull(bills().billFromReply("u-1", reply(methodId = 19), known))          // a method this till does not book on
        assertNull(bills().billFromReply("u-1", reply(partnerId = 83), known))         // a customer the sale did not carry
        assertNull(bills().billFromReply("u-1", reply(), known.copy(taxNames = emptyMap())))
        assertNull(bills().billFromReply("other", reply(), known))                     // not this order
        assertEquals(0, server.requestCount)
    }
}
