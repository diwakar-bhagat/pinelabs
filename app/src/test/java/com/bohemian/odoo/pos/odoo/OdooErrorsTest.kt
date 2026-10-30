package com.bohemian.odoo.pos.odoo

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Every Odoo failure gets a kind, from the error bodies JSON-2 really sends (Odoo 19 `Json2Dispatcher`,
 * probed read-only on the live database, 2026-10-30).
 */
class OdooErrorsTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun client() = OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { "k" },
        throttle = OdooJson2Client.Throttle(tapBudgetMs = 200, saleBudgetMs = 200, backgroundBudgetMs = 200, firstBackoffMs = 50, jitter = { 0.5 }))

    private fun error(status: Int, name: String, message: String) =
        MockResponse().setResponseCode(status).setBody("""{"name": "$name", "message": "$message", "arguments": [], "context": {}, "debug": ""}""")

    private suspend fun kindOf(response: MockResponse): OdooErrorKind {
        server.enqueue(response)
        return OdooErrors.kind(runCatching { client().call("pos.order", "sync_from_ui", jsonOf()) }.exceptionOrNull()!!)
    }

    @Test fun `Odoo refusing the request is REJECTED, whatever the status`() = runBlocking {
        assertEquals(OdooErrorKind.REJECTED, kindOf(error(422, "odoo.exceptions.ValidationError", "The paid amount is different from the total amount of the order.")))
        assertEquals(OdooErrorKind.REJECTED, kindOf(error(500, "builtins.ValueError", "Invalid field res.partner.x_no_such_field in condition")))
        assertEquals(OdooErrorKind.REJECTED, kindOf(error(404, "werkzeug.exceptions.NotFound", "The method 'res.partner.no_such_method' does not exist")))
        assertEquals(OdooErrorKind.REJECTED, kindOf(error(404, "odoo.exceptions.MissingError", "Record does not exist or has been deleted.")))
        assertEquals(OdooErrorKind.REJECTED, kindOf(error(403, "odoo.exceptions.AccessError", "You are not allowed to access 'Point of Sale Orders'.")))
    }

    @Test fun `no open session is a wait, not a refusal`() = runBlocking {
        assertEquals(OdooErrorKind.NO_SESSION,
            kindOf(error(422, "odoo.exceptions.UserError", "No open session available. Please open a new session to capture the order.")))
    }

    @Test fun `a rejected key is AUTH and pauses background calls until the next probe`() = runBlocking {
        server.enqueue(error(401, "odoo.exceptions.AccessDenied", "Access Denied"))
        val odoo = client()
        val e = runCatching { odoo.call("pos.config", "search_read", jsonOf()) }.exceptionOrNull()!!
        assertEquals(OdooErrorKind.AUTH, OdooErrors.kind(e))
        assertTrue(odoo.keyRejected.value)
        // Background work does not call Odoo again until the probe time.
        val bg = runCatching { kotlinx.coroutines.withContext(OdooLane.BACKGROUND) { odoo.call("pos.config", "search_read", jsonOf()) } }.exceptionOrNull()
        assertTrue(bg is OdooException.Auth)
        assertEquals(1, server.requestCount)
        // The cashier's next call goes through, and a success clears the red bar.
        server.enqueue(MockResponse().setBody("[]"))
        odoo.call("pos.config", "search_read", jsonOf())
        assertFalse(odoo.keyRejected.value)
    }

    @Test fun `an AccessError is the user's rights, not the key`() = runBlocking {
        server.enqueue(error(403, "odoo.exceptions.AccessError", "You are not allowed to create 'Contact'"))
        val odoo = client()
        val e = runCatching { odoo.call("res.partner", "create", jsonOf()) }.exceptionOrNull()
        assertTrue(e is OdooException.Server)
        assertFalse(odoo.keyRejected.value)
    }

    @Test fun `passing trouble is TRANSIENT`() {
        assertEquals(OdooErrorKind.TRANSIENT, OdooErrors.kind(OdooException.Network(java.io.IOException("timeout"))))
        assertEquals(OdooErrorKind.TRANSIENT, OdooErrors.server(429, "TooManyRequests", "Odoo is busy"))
        assertEquals(OdooErrorKind.TRANSIENT, OdooErrors.server(503, null, "HTTP 503"))
        assertEquals(OdooErrorKind.TRANSIENT, OdooErrors.server(409, "odoo.exceptions.LockError", "Cannot lock the record"))
        assertEquals(OdooErrorKind.TRANSIENT, OdooErrors.server(500, "psycopg2.errors.SerializationFailure", "could not serialize access"))
        assertEquals(OdooErrorKind.TRANSIENT, OdooErrors.server(200, "BadResponse", "Odoo sent an answer the till cannot read"))
    }
}
