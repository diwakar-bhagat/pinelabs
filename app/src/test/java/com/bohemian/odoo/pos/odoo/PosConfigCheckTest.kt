package com.bohemian.odoo.pos.odoo

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The saved POS config and payment methods are revalidated against Odoo on every load. */
class PosConfigCheckTest {

    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun catalog() = CatalogService(OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { "k" }))

    private val cfg = PosConfig(id = 9, name = "E700", currentSessionId = 3, sessionOpen = true,
        paymentMethodIds = listOf(1, 5), companyName = "ZenZebra")

    @Test fun `saved methods still on the config pass`() {
        assertNull(cfg.paymentMethodProblem(terminalMethodId = 5, cashMethodId = 1))
    }

    @Test fun `no cash method set is allowed`() {
        assertNull(cfg.paymentMethodProblem(terminalMethodId = 5, cashMethodId = 0))
    }

    @Test fun `terminal method removed from the config is refused`() {
        assertNotNull(cfg.paymentMethodProblem(terminalMethodId = 7, cashMethodId = 1))
    }

    @Test fun `terminal method never set is refused`() {
        assertNotNull(cfg.paymentMethodProblem(terminalMethodId = 0, cashMethodId = 1))
    }

    @Test fun `cash method removed from the config is refused`() {
        assertNotNull(cfg.paymentMethodProblem(terminalMethodId = 5, cashMethodId = 8))
    }

    @Test fun `archived or deleted config reads as null`() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        assertNull(catalog().config(9))
        assertEquals("/json/2/pos.config/search_read", server.takeRequest().path)
    }

    @Test fun `existing config is read by id`() = runTest {
        server.enqueue(MockResponse().setBody(
            """[{"id": 9, "name": "E700", "current_session_id": [3, "POS/003"], "current_session_state": "opened",
               "payment_method_ids": [1, 5], "company_id": [1, "ZenZebra"], "cash_control": true}]"""))
        val c = catalog().config(9)!!
        assertEquals(9, c.id)
        assertEquals(listOf(1, 5), c.paymentMethodIds)
        assertEquals(true, c.sessionOpen)
    }
}
