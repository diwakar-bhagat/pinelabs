package com.bohemian.odoo.pos.odoo

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The catalogue load: every eligible product, checked against Odoo's own count (2,421 live on 2026-10-27). */
class CatalogServiceTest {

    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun service() = CatalogService(OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { "k" }))

    private val cfg = PosConfig(id = 1, name = "ZenZebra", currentSessionId = 7, sessionOpen = true,
        paymentMethodIds = listOf(22), companyName = "ZenZebra", companyId = 1)

    private fun row(id: Int, name: String = "Product $id", categ: List<Int> = emptyList(), barcode: String? = null) =
        """{"id": $id, "display_name": "$name", "lst_price": 100.0, "barcode": ${barcode?.let { "\"$it\"" } ?: "false"},
           "default_code": false, "taxes_id": [30], "l10n_in_hsn_code": false, "pos_categ_ids": $categ, "write_date": "2026-10-27 10:00:00"}"""

    private fun count(n: Int) = server.enqueue(MockResponse().setBody("$n"))
    private fun page(ids: IntRange) = server.enqueue(MockResponse().setBody(ids.joinToString(",", "[", "]") { row(it) }))
    private fun page(ids: List<Int>) = server.enqueue(MockResponse().setBody(ids.joinToString(",", "[", "]") { row(it) }))

    private fun requests(): List<Pair<String, JsonObject>> = (0 until server.requestCount).map {
        val r = server.takeRequest()
        r.path!!.substringAfterLast('/') to Json.parseToJsonElement(r.body.readUtf8()).jsonObject
    }

    @Test fun `walks every page until Odoo's count is reached`() = runTest {
        count(1234)
        page(1..500); page(501..1000); page(1001..1234)
        val got = service().products(cfg, pageSize = 500)
        assertEquals(1234, got.size)
        assertEquals((1..1234).toSet(), got.map { it.id }.toSet())
        val calls = requests()
        assertEquals(listOf("search_count", "search_read", "search_read", "search_read"), calls.map { it.first })
        assertEquals(listOf(0, 500, 1000), calls.drop(1).map { it.second["offset"]!!.jsonPrimitive.int })
        assertEquals("id", calls[1].second["order"]!!.jsonPrimitive.content) // stable paging
    }

    @Test fun `products without a menu tab are kept`() = runTest {
        count(2)
        server.enqueue(MockResponse().setBody("[${row(1, "Chai", categ = listOf(1))}, ${row(2, "A Fragrance Story 50 ml")}]"))
        val got = service().products(cfg)
        assertEquals(listOf("A Fragrance Story 50 ml", "Chai"), got.map { it.name }) // sorted by name
        assertTrue(got.first().categIds.isEmpty())
    }

    @Test fun `a duplicate across pages is merged once`() = runTest {
        count(5)
        page(listOf(1, 2, 3)); page(listOf(3, 4, 5)); page(emptyList())
        val got = service().products(cfg, pageSize = 3)
        assertEquals(listOf(1, 2, 3, 4, 5), got.map { it.id })
    }

    @Test fun `a server that caps the page below the asked size is still walked to the end`() = runTest {
        count(6)
        page(1..2); page(3..4); page(5..6)
        val got = service().products(cfg, pageSize = 500)
        assertEquals(6, got.size)
    }

    @Test fun `products missing twice is an error, never a smaller catalogue`() = runTest {
        // A short page below the count is not the end: the walk asks again and gets nothing.
        count(10); page(1..8); page(emptyList())
        count(10); page(1..8); page(emptyList())
        try {
            service().products(cfg)
            fail("expected CatalogIncomplete")
        } catch (e: CatalogIncomplete) {
            assertEquals(10, e.expected)
            assertEquals(8, e.got)
        }
    }

    @Test fun `a second pass settles products archived while paging`() = runTest {
        count(10); page(1..8); page(emptyList())
        count(8); page(1..8)
        assertEquals(8, service().products(cfg).size)
    }

    @Test fun `a server error while paging fails the load`() = runTest {
        count(1000)
        page(1..500)
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"name": "builtins.Exception", "message": "boom"}"""))
        try {
            service().products(cfg, pageSize = 500)
            fail("expected an Odoo error")
        } catch (e: OdooException) {
            // nothing returned, so nothing is cached or shown as synced
        }
    }

    @Test fun `domain follows the Odoo 19 POS rules`() {
        val open = CatalogService.productDomain(cfg).toString()
        assertTrue(open, open.contains("[available_in_pos, =, true]") && open.contains("[sale_ok, =, true]"))
        assertTrue(open, open.contains("|") && open.contains("[company_id, parent_of, [1]]"))
        assertTrue(open, !open.contains("pos_categ_ids"))
        val limited = CatalogService.productDomain(cfg.copy(limitCategories = true, availableCategIds = listOf(5, 6)))
        assertTrue(limited.contains(listOf("pos_categ_ids", "child_of", listOf(5, 6))))
    }

    @Test fun `limited config shows only its tabs and their children`() = runTest {
        server.enqueue(MockResponse().setBody("""[
            {"id": 1, "name": "Chai & Coffee", "parent_id": false, "sequence": 1, "color": 8},
            {"id": 3, "name": "WhatsApp Menu", "parent_id": false, "sequence": 9, "color": 0},
            {"id": 4, "name": "WA HO", "parent_id": [3, "WhatsApp Menu"], "sequence": 0, "color": 4},
            {"id": 5, "name": "Chai Bina Chain Kahan Re", "parent_id": [4, "WhatsApp Menu / WA HO"], "sequence": 0, "color": 8}
        ]"""))
        val got = service().categories(cfg.copy(limitCategories = true, availableCategIds = listOf(4)))
        assertEquals(listOf(4, 5), got.map { it.id })
    }
}
