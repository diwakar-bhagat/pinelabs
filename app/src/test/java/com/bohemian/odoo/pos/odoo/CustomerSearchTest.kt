package com.bohemian.odoo.pos.odoo

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** "Name or mobile" in the customer dialog: names A→Z, 20 at a time; digits search the mobile. */
class CustomerSearchTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun service() = CustomerService(OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { "k" }))

    @Test fun `letters search the name or email, A to Z, from the given offset`() = runTest {
        // What the live DB answered for "diw" on 2026-10-28 (phones made up).
        server.enqueue(MockResponse().setBody("""[
            {"id": 501, "name": "Astha diwedi", "phone": "+91 90000 00001"},
            {"id": 19, "name": "Diwakar", "phone": "+91 98765 43210"},
            {"id": 502, "name": "Diwan", "phone": false},
            {"id": 503, "name": "Diwas", "phone": "+91 90000 00003"}]"""))
        val found = service().search("diw", offset = 20)
        assertEquals(listOf("Astha diwedi", "Diwakar", "Diwan", "Diwas"), found.map { it.name })
        val req = server.takeRequest()
        assertEquals("/json/2/res.partner/search_read", req.path)
        val body = req.body.readUtf8()
        assertTrue(body, "\"domain\":[\"|\",[\"name\",\"ilike\",\"diw\"],[\"email\",\"ilike\",\"diw\"]]" in body)
        assertTrue(body, "\"offset\":20" in body && "\"limit\":20" in body && "\"order\":\"name, id\"" in body)
    }

    @Test fun `digits search the last digits of the mobile, in the same order and pages`() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        service().search("3210")
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, "[\"phone_sanitized\",\"=ilike\",\"%3210\"]" in body)
        assertTrue(body, "\"offset\":0" in body && "\"order\":\"name, id\"" in body)
    }

    @Test fun `too little typed asks Odoo nothing`() = runTest {
        val svc = service()
        assertTrue(svc.search("d").isEmpty())
        assertTrue(svc.search("321").isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test fun `what counts as a mobile and what as a name`() {
        assertTrue(CustomerService.isPhoneQuery("+91 98765"))
        assertFalse(CustomerService.isPhoneQuery("ravi 2"))
        assertTrue(CustomerService.isSearchable("ra"))
        assertFalse(CustomerService.isSearchable("r"))
        assertTrue(CustomerService.isSearchable("9876"))
        assertFalse(CustomerService.isSearchable("987"))
    }
}
