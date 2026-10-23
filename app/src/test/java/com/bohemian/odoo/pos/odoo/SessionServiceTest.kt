package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionServiceTest {

    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun service(pending: PendingWork = PendingWork(0, 0)) = SessionService(
        OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { "k" }),
        pendingWork = { pending },
    )

    private fun ok(body: String) = server.enqueue(MockResponse().setBody(body))

    /** (method, body) of each request Odoo received, in order. */
    private fun calls(): List<Pair<String, JsonObject>> = (0 until server.requestCount).map {
        val r = server.takeRequest()
        r.path!!.substringAfterLast('/') to Json.parseToJsonElement(r.body.readUtf8()).jsonObject
    }

    // ------------------------------------------------------------ I9

    @Test fun `unresolved payment blocks close without calling Odoo`() = runTest {
        val r = service(PendingWork(unresolvedAttempts = 1, unsyncedOrders = 0)).close(7, cashControl = false, countedCash = null, notes = "")
        assertEquals(CloseResult.Blocked(PendingWork(1, 0)), r)
        assertEquals(0, server.requestCount)
    }

    @Test fun `unsynced paid order blocks close without calling Odoo`() = runTest {
        val r = service(PendingWork(0, 2)).close(7, cashControl = true, countedCash = Paise(0), notes = "")
        assertEquals(CloseResult.Blocked(PendingWork(0, 2)), r)
        assertEquals(0, server.requestCount)
    }

    @Test fun `pending work is clear only when all counts are zero`() {
        assertTrue(PendingWork(0, 0).isClear)
        assertFalse(PendingWork(1, 0).isClear)
        assertFalse(PendingWork(0, 1).isClear)
        assertFalse(PendingWork(0, 0, openVoids = 1).isClear)
    }

    @Test fun `open void blocks close without calling Odoo`() = runTest {
        val r = service(PendingWork(0, 0, openVoids = 1)).close(7, cashControl = false, countedCash = null, notes = "")
        assertEquals(CloseResult.Blocked(PendingWork(0, 0, 1)), r)
        assertEquals(0, server.requestCount)
    }

    // ------------------------------------------------------------ Odoo 19 closing sequence

    @Test fun `cash control posts counted cash then closes, in web POS order`() = runTest {
        ok("""{"successful": true}""")
        ok("null")
        ok("""{"successful": true}""")

        val r = service().close(7, cashControl = true, countedCash = Paise(152050), notes = "short 10")

        assertEquals(CloseResult.Closed, r)
        val c = calls()
        assertEquals(listOf("post_closing_cash_details", "update_closing_control_state_session", "close_session_from_ui"), c.map { it.first })
        assertEquals("[7]", c[0].second["ids"].toString())
        assertEquals("1520.50", c[0].second["counted_cash"].toString())
        assertEquals("\"short 10\"", c[1].second["notes"].toString())
        assertEquals("[]", c[2].second["bank_payment_method_diff_pairs"].toString())
    }

    @Test fun `without cash control no counted cash is posted`() = runTest {
        ok("null")
        ok("""{"successful": true}""")

        assertEquals(CloseResult.Closed, service().close(7, cashControl = false, countedCash = null, notes = ""))
        assertEquals(listOf("update_closing_control_state_session", "close_session_from_ui"), calls().map { it.first })
    }

    @Test fun `refused cash details stop before the session is touched`() = runTest {
        ok("""{"successful": false, "message": "You cannot close the POS while there are still draft orders for the day.", "redirect": false}""")

        val r = service().close(7, cashControl = true, countedCash = Paise(0), notes = "")

        assertEquals(CloseResult.Refused("You cannot close the POS while there are still draft orders for the day.", false), r)
        assertEquals(1, server.requestCount)
    }

    @Test fun `session already closed elsewhere returns Odoo's explanation`() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"name": "odoo.exceptions.UserError", "message": "This session is already closed."}"""))
        ok("""{"successful": false, "type": "alert", "title": "Session already closed", "message": "The session has been already closed by another User.", "redirect": true}""")

        val r = service().close(7, cashControl = false, countedCash = null, notes = "")

        assertEquals(CloseResult.Refused("The session has been already closed by another User.", true), r)
    }

    // ------------------------------------------------------------ parsing

    @Test fun `successful result parses as no refusal`() {
        assertNull(SessionService.parseResult(Json.parseToJsonElement("""{"successful": true}""")))
        assertNull(SessionService.parseResult(Json.parseToJsonElement("null")))
    }

    @Test fun `closing data is read into paise`() {
        val s = SessionService.parseSummary(Json.parseToJsonElement("""
            {
              "orders_details": {"quantity": 12, "amount": 8390.5},
              "opening_notes": false,
              "default_cash_details": {"name": "Cash", "amount": 2600.0, "opening": 1000.0, "payment_amount": 1700.0, "moves": [], "id": 3},
              "non_cash_payment_methods": [
                {"name": "Pine Labs E700 (A2A)", "amount": 6690.5, "number": 9, "id": 8, "type": "bank"}
              ],
              "is_manager": false,
              "amount_authorized_diff": null
            }
        """).jsonObject)

        assertEquals(12, s.orderCount)
        assertEquals(Paise(839050), s.orderTotal)
        val cash = s.cash!!
        assertEquals(Paise(100000), cash.opening)
        assertEquals(Paise(170000), cash.payments)
        assertEquals(Paise(260000), cash.expected)
        assertEquals(Paise(-10000), cash.moves) // a ₹100 cash-out
        assertEquals(listOf(MethodTotal(8, "Pine Labs E700 (A2A)", Paise(669050), 9)), s.nonCash)
        assertEquals(Paise(839050), s.paymentsTotal)
    }

    @Test fun `no cash method means no drawer`() {
        val s = SessionService.parseSummary(Json.parseToJsonElement("""
            {"orders_details": {"quantity": 0, "amount": 0}, "default_cash_details": {}, "non_cash_payment_methods": []}
        """).jsonObject)
        assertNull(s.cash)
        assertEquals(Paise.ZERO, s.paymentsTotal)
    }
}
