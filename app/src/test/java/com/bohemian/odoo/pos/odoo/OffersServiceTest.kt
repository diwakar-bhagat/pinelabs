package com.bohemian.odoo.pos.odoo

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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

/** The store's offers from Odoo's POS loader, shaped as HQ27GGN's session answered on 2026-10-28 (codes made up). */
class OffersServiceTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private val loadData = """{
      "loyalty.program": [
        {"id": 28, "name": "HQ27 combo", "program_type": "promotion", "trigger": "auto", "applies_on": "current", "is_nominative": false,
         "date_from": "2026-10-28", "date_to": "2026-10-28", "limit_usage": false, "max_usage": 0, "total_order_count": 3,
         "pricelist_ids": [], "rule_ids": [80], "reward_ids": [40]},
        {"id": 9, "name": "Sampling", "program_type": "promo_code", "trigger": "with_code", "applies_on": "current", "is_nominative": false,
         "date_from": false, "date_to": false, "limit_usage": false, "max_usage": 0, "total_order_count": 0,
         "pricelist_ids": [], "rule_ids": [30], "reward_ids": [12]},
        {"id": 1, "name": "Gift Cards", "program_type": "gift_card", "trigger": "auto", "applies_on": "future", "is_nominative": false,
         "date_from": false, "date_to": false, "limit_usage": false, "max_usage": 0, "total_order_count": 0,
         "pricelist_ids": [], "rule_ids": [], "reward_ids": []}
      ],
      "loyalty.rule": [
        {"id": 80, "program_id": 28, "mode": "auto", "code": false, "reward_point_mode": "order", "reward_point_amount": 1.0,
         "minimum_amount": 0.0, "minimum_amount_tax_mode": "incl", "minimum_qty": 1, "any_product": false, "valid_product_ids": [21796]},
        {"id": 30, "program_id": 9, "mode": "with_code", "code": "SAMPLEX", "reward_point_mode": "order", "reward_point_amount": 1.0,
         "minimum_amount": 0.0, "minimum_amount_tax_mode": "incl", "minimum_qty": 0, "any_product": true, "valid_product_ids": []}
      ],
      "loyalty.reward": [
        {"id": 40, "program_id": 28, "reward_type": "discount", "description": "Combo: 89 off momos", "required_points": 1.0, "discount": 89.0,
         "discount_mode": "per_order", "discount_applicability": "specific", "all_discount_product_ids": [], "discount_max_amount": 0.0,
         "discount_line_product_id": 34936, "reward_product_ids": [], "reward_product_id": false, "reward_product_qty": 1,
         "multi_product": false, "is_global_discount": false, "clear_wallet": false, "reward_product_domain": "[[\"id\", \"in\", [21916]]]"},
        {"id": 12, "program_id": 9, "reward_type": "discount", "description": "Free sample", "required_points": 1.0, "discount": 100.0,
         "discount_mode": "percent", "discount_applicability": "cheapest", "all_discount_product_ids": [], "discount_max_amount": 0.0,
         "discount_line_product_id": 29200, "reward_product_ids": [], "reward_product_id": false, "reward_product_qty": 1,
         "multi_product": false, "is_global_discount": false, "clear_wallet": false, "reward_product_domain": "[[1, \"=\", 1]]"}
      ]
    }"""

    @Test fun `each discount's products come from Odoo reading its domain, every product needs no list`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/json/2/pos.session/load_data" -> MockResponse().setBody(loadData)
                "/json/2/product.product/search" -> MockResponse().setBody("[21916]")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val programs = OffersService(OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { "k" })).load(397)
        assertEquals(listOf(28, 9, 1), programs.map { it.id })
        val combo = programs.first { it.id == 28 }.rewards.single()
        assertEquals(setOf(21916), combo.discountProductIds)
        assertFalse(combo.discountAllProducts)
        val sample = programs.first { it.id == 9 }
        assertTrue(sample.rewards.single().discountAllProducts)
        assertTrue(sample.rewards.single().discounts(12345))
        assertEquals("SAMPLEX", sample.rules.single().code)
        assertEquals("2026-10-28", programs.first().dateTo)
        assertFalse(programs.first { it.id == 1 }.supported)
        // One search, for the combo only; the load and that search are all the calls.
        val calls = (0 until server.requestCount).map { server.takeRequest() }
        assertEquals(1, calls.count { it.path == "/json/2/product.product/search" })
        assertTrue(calls.first { it.path == "/json/2/product.product/search" }.body.readUtf8().contains("[[\"id\",\"in\",[21916]]]"))
    }

    @Test fun `every product is recognised however Odoo spaces it`() {
        assertTrue(OffersService.isEveryProduct(Json.parseToJsonElement("[[1, \"=\", 1]]").jsonArray))
        assertFalse(OffersService.isEveryProduct(Json.parseToJsonElement("[[\"categ_id\", \"child_of\", 5]]").jsonArray))
    }
}
