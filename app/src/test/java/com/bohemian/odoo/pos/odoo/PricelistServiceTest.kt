package com.bohemian.odoo.pos.odoo

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Products a store's pricelist prices differently from the list price are held back from the till. */
class PricelistServiceTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun service() = PricelistService(OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { "k" }))

    private val klj = PosConfig(id = 2, name = "KLJ", currentSessionId = 395, sessionOpen = true, paymentMethodIds = listOf(4, 5, 7),
        companyName = "ZenZebra", pricelistId = 1)

    private fun answer(rules: String, search: (String) -> String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/json/2/product.pricelist.item/search_read" -> MockResponse().setBody(rules)
                "/json/2/product.product/search" -> MockResponse().setBody(search(request.body.readUtf8()))
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @Test fun `pricelist 1 today, shelf rentals only, holds back no POS product`() = runTest {
        answer("""[{"id": 2, "applied_on": "1_product", "product_tmpl_id": [30237, "Shelf Rental - KLJ (Noida)"], "product_id": false,
                    "categ_id": false, "date_end": false}]""") { "[]" }
        val hold = service().hold(klj, "2026-10-28 11:00:00")
        assertEquals(1, hold.pricelistId)
        assertFalse(hold.everything)
        assertTrue(hold.productIds.isEmpty())
    }

    @Test fun `a rule on a POS category holds back exactly the products Odoo says it covers`() = runTest {
        answer("""[{"id": 9, "applied_on": "2_product_category", "product_tmpl_id": false, "product_id": false,
                    "categ_id": [12, "Beverages"], "date_end": false}]""") { body ->
            assertTrue(body.contains("[\"categ_id\",\"child_of\",12]"))
            assertTrue(body.contains("[\"available_in_pos\",\"=\",true]"))
            "[8625, 8626]"
        }
        val hold = service().hold(klj, "2026-10-28 11:00:00")
        assertTrue(hold.holds(8625))
        assertFalse(hold.holds(27857))
    }

    @Test fun `a global rule holds back every product`() = runTest {
        answer("""[{"id": 10, "applied_on": "3_global", "product_tmpl_id": false, "product_id": false, "categ_id": false, "date_end": false}]""") { "[]" }
        assertTrue(service().hold(klj, "2026-10-28 11:00:00").holds(1))
    }

    @Test fun `an ended rule no longer counts`() = runTest {
        answer("""[{"id": 10, "applied_on": "3_global", "product_tmpl_id": false, "product_id": false, "categ_id": false,
                    "date_end": "2026-10-27 18:29:59"}]""") { "[]" }
        assertFalse(service().hold(klj, "2026-10-28 11:00:00").holds(1))
    }

    @Test fun `a store with no pricelist is never asked`() = runTest {
        assertEquals(PricelistHold.NONE, service().hold(klj.copy(pricelistId = null), "2026-10-28 11:00:00"))
        assertEquals(0, server.requestCount)
    }
}
