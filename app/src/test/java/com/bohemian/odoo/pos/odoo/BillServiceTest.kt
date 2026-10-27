package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.util.TimeZone

/** The bill read back from Odoo: bill 000020 of 2026-10-27, as the live DB returned it. */
class BillServiceTest {

    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun service() = BillService(OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { "k" }))
    private fun ok(body: String) = server.enqueue(MockResponse().setBody(body))
    private fun paths() = (0 until server.requestCount).map { server.takeRequest().path!!.removePrefix("/json/2/") }

    private fun enqueueBill000020(zz: String) = enqueueBill000020(MockResponse().setBody(zz))

    private fun enqueueBill000020(zz: MockResponse) {
        ok("""[{"id": 15738, "name": "ZenZebra - 000020", "pos_reference": "260-1-000020", "date_order": "2026-10-27 12:04:16",
            "partner_id": [19, "Diwakar"], "cashier": "Ankur", "config_id": [1, "ZenZebra"], "amount_total": 5.0,
            "amount_tax": 0.24, "lines": [28109], "payment_ids": [15661], "access_token": "tok"}]""")
        ok("""[{"id": 28109, "full_product_name": "Go desi Masala Jamun Popz 8gm", "product_id": [9001, "Go desi Masala Jamun Popz 8gm"],
            "qty": 1.0, "price_subtotal_incl": 5.0, "discount": 0.0, "tax_ids": [31]}]""")
        ok("""[{"id": 31, "name": "5% GST S"}]""")
        ok("""[{"id": 15661, "payment_method_id": [2, "Cash"], "amount": 5.0}]""")
        ok("""[{"id": 19, "phone": "+91 70489 80055"}]""")
        server.enqueue(zz)
    }

    @Test fun `reads the order the way the ZenZebra Bill report prints it`() = runTest {
        enqueueBill000020("""[{"id": 15738, "x_zz_show": false, "x_zz_earned_txt": "0.10", "x_zz_used": 0.0,
            "x_zz_used_txt": "0", "x_zz_balance_txt": "35.90", "x_zz_expiry": "26 Nov 2026"}]""")
        val b = service().bill("3c5e0cf3-41ea-45db-a933-4e5c857df1a4")!!
        assertEquals("ZenZebra - 000020", b.name)
        assertEquals("260-1-000020", b.posReference)
        assertEquals("ZenZebra", b.storeName)
        assertEquals("Diwakar", b.customerName)
        assertEquals("+91 70489 80055", b.customerPhone)
        assertEquals("Ankur", b.cashier)
        assertEquals(listOf(OdooBillLine("Go desi Masala Jamun Popz 8gm", BigDecimal("1.0"), BigDecimal("0.0"), listOf("5% GST S"), Paise(500))), b.lines)
        assertEquals(Paise(500), b.amountTotal)
        assertEquals(Paise(24), b.amountTax)
        assertEquals(listOf(OdooBillPayment("Cash", Paise(500))), b.payments)
        assertEquals("tok", b.accessToken)
        assertNull(b.zzCash) // Odoo hides the block on this bill (x_zz_show false), and so does the PDF
        // Odoo stores UTC; the paper bill shows the terminal's time.
        assertEquals("27 oct 2026, 05:34 pm", b.localDate(TimeZone.getTimeZone("Asia/Kolkata")).lowercase())
        assertEquals(
            listOf("pos.order/search_read", "pos.order.line/read", "account.tax/read", "pos.payment/read", "res.partner/read", "pos.order/read"),
            paths(),
        )
    }

    @Test fun `the ZZ Cash block comes from Odoo's own texts`() = runTest {
        enqueueBill000020("""[{"id": 15738, "x_zz_show": true, "x_zz_earned_txt": "401.44", "x_zz_used": 400.0,
            "x_zz_used_txt": "400", "x_zz_balance_txt": "36.04", "x_zz_expiry": "26 Nov 2026"}]""")
        assertEquals(OdooBillZzCash("401.44", "400", "36.04", "26 Nov 2026"), service().bill("u")!!.zzCash)
    }

    @Test fun `a missing ZZ Cash field drops only that block`() = runTest {
        enqueueBill000020(MockResponse().setResponseCode(500)
            .setBody("""{"name": "builtins.ValueError", "message": "Invalid field 'x_zz_show' on model 'pos.order'"}"""))
        val b = service().bill("u")!!
        assertEquals("ZenZebra - 000020", b.name)
        assertEquals(1, b.lines.size)
        assertNull(b.zzCash)
    }

    @Test fun `order not in Odoo yet gives no bill`() = runTest {
        ok("[]")
        assertNull(service().bill("still-in-outbox"))
    }

    @Test fun `address line like the template`() {
        assertEquals(
            "A-11, 2nd Floor, A Block Rd, DDA Sheds, Pocket A, Pocket A, Okhla Phase I, New Delhi 110020",
            BillService.addressLine("A-11, 2nd Floor, A Block Rd, DDA Sheds, Pocket A", "Pocket A, Okhla Phase I", "New Delhi", "110020"),
        )
        assertEquals("New Delhi", BillService.addressLine(null, " ", "New Delhi", null))
        assertNull(BillService.addressLine(null, null, null, null))
    }
}
