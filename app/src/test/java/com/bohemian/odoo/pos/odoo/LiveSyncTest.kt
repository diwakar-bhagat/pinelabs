package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Live Odoo data: what changed since the last look, and the catalogue after a product change. */
class LiveSyncTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun client() = OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { "k" })

    private fun stamps(vararg changed: Pair<String, String>) =
        LiveStamps(LiveSync.WATCHED.keys.associateWith { "2026-10-28 05:00:00" } + changed)

    @Test fun `each changed model names the part of the till to refresh`() {
        val before = stamps()
        assertEquals(emptySet<LiveArea>(), stamps().changedSince(before))
        assertEquals(setOf(LiveArea.PRODUCTS), stamps("product.template" to "2026-10-28 07:56:40").changedSince(before))
        assertEquals(setOf(LiveArea.TAXES, LiveArea.LOYALTY),
            stamps("account.tax" to "2026-10-28 08:00:00", "loyalty.reward" to "2026-10-28 08:00:01").changedSince(before))
        assertEquals(setOf(LiveArea.BILL_TEXT), stamps("ir.ui.view" to "2026-10-28 08:00:00").changedSince(before))
    }

    @Test fun `products are fetched from the older of the two product stamps`() =
        assertEquals("2026-10-28 05:00:00", stamps("product.product" to "2026-10-28 07:56:40").productsSince)

    @Test fun `stamps are Odoo's newest write_date per model, archived records included`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val model = request.path!!.removePrefix("/json/2/").substringBefore('/')
                return MockResponse().setBody(if (model == "pos.note") "[]" else """[{"id": 1, "write_date": "2026-10-28 07:56:40"}]""")
            }
        }
        val s = LiveSync(client()).read()
        assertEquals("2026-10-28 07:56:40", s.byModel["product.template"])
        assertNull(s.byModel["pos.note"])
        val bodies = (0 until server.requestCount).map { server.takeRequest() }.associate { it.path!! to it.body.readUtf8() }
        assertEquals(LiveSync.WATCHED.size, bodies.size)
        bodies.values.forEach { assertTrue(it, "\"active_test\":false" in it && "\"order\":\"write_date desc, id desc\"" in it) }
        assertTrue(bodies.getValue("/json/2/ir.ui.view/search_read").contains("[\"key\",\"=\",\"x_zenzebra.pos_bill\"]"))
    }

    private fun p(id: Int, name: String, rupees: Long) = Product(id, name, Paise.ofRupees(rupees), null, null, listOf(31))

    @Test fun `a changed price replaces the old one and an archived product drops out`() {
        val coffee = p(8625, "Cookie Butter Coffee", 180)
        val momos = p(9001, "Veg Momos", 120)
        val chai = p(8201, "Adrak Elaichi Chai", 60)
        val dearer = coffee.copy(price = Paise.ofRupees(190))
        val m = CatalogService.merge(listOf(chai, coffee, momos), listOf(dearer), ids = listOf(8201, 8625))
        assertEquals(listOf(chai, dearer), m.products)
        assertEquals(1, m.removed)
        assertTrue(m.missing.isEmpty())
    }

    @Test fun `a product Odoo sells that the delta did not bring is listed to be read`() {
        val m = CatalogService.merge(listOf(p(1, "A", 10)), emptyList(), ids = listOf(1, 77))
        assertEquals(listOf(77), m.missing)
    }

    @Test fun `the delta asks for products whose product or template changed since the stamp`() = runTest {
        server.enqueue(MockResponse().setBody("""[{"id": 8625, "display_name": "Cookie Butter Coffee", "lst_price": 190.0, "taxes_id": [31],
            "pos_categ_ids": [1], "write_date": "2026-10-28 07:56:40"}]"""))
        val cfg = PosConfig(id = 1, name = "ZenZebra", currentSessionId = 396, sessionOpen = true, paymentMethodIds = listOf(2, 22),
            companyName = "ZenZebra", companyId = 1)
        val changed = CatalogService(client()).productsChangedSince(cfg, "2026-10-28 05:00:00")!!
        assertEquals(Paise.ofRupees(190), changed.single().price)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, "\"|\",[\"write_date\",\">=\",\"2026-10-28 05:00:00\"],[\"product_tmpl_id.write_date\",\">=\",\"2026-10-28 05:00:00\"]" in body)
        assertTrue(body, "[\"available_in_pos\",\"=\",true]" in body)
    }
}
