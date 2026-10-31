package com.bohemian.odoo.pos.odoo

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The till's customer index (0.9.0): Odoo's first-or-last-digits match, with no call. Names and numbers are made up. */
class CustomerIndexTest {

    private fun row(id: Int, name: String, e164: String, state: Int? = null) =
        IndexedCustomer(Customer(id, name, e164, stateId = state), e164)

    private val rows = listOf(
        row(1, "Meera", "+919810011111"),
        row(2, "arjun", "+919810022222"),
        row(3, "Kabir", "+917000012345"),
        row(4, "Zoya", "+919811112345"),
        row(5, "Dana", "+14155559810"), // a US number: matched by its last digits only, as Odoo's domain does
    )

    private fun index() = CustomerIndex().apply { replaceAll(rows, at = 1L) }

    @Test fun `first digits, last digits, both together, A to Z`() {
        val idx = index()
        assertEquals(listOf("arjun", "Meera"), idx.byPhone("9810").filter { it.id != 5 }.map { it.name })
        assertEquals(listOf("arjun", "Dana", "Meera"), idx.byPhone("9810").map { it.name }) // Dana ends in 9810; case-blind A→Z
        assertEquals(listOf("Kabir", "Zoya"), idx.byPhone("12345").map { it.name })
        assertEquals(listOf("Zoya"), idx.byPhone("98111").map { it.name })
    }

    @Test fun `a full number with 91 or 0 in front, and too few digits`() {
        val idx = index()
        assertEquals(listOf(3), idx.byPhone("+91 70000 12345").map { it.id })
        assertEquals(listOf(3), idx.byPhone("07000012345").map { it.id })
        assertTrue(idx.byPhone("981").isEmpty())
        assertTrue(idx.byPhone("4155").isEmpty()) // a foreign number's first digits are not a match in Odoo either
    }

    @Test fun `pages follow the same order`() {
        val idx = index()
        assertEquals(listOf("arjun", "Dana"), idx.byPhone("9810", offset = 0, limit = 2).map { it.name })
        assertEquals(listOf("Meera"), idx.byPhone("9810", offset = 2, limit = 2).map { it.name })
    }

    @Test fun `exact mobile, and nothing before the first load`() {
        assertFalse(CustomerIndex().ready)
        assertNull(CustomerIndex().exact("+919810011111"))
        val idx = index()
        assertTrue(idx.ready)
        assertEquals(1, idx.exact("+919810011111")?.id)
        assertNull(idx.exact("+919810099999"))
    }

    @Test fun `a delta moves a changed number and drops archived customers`() {
        val idx = index()
        idx.apply(listOf(row(1, "Meera", "+916000033333")), removed = listOf(4))
        assertNull(idx.exact("+919810011111"))
        assertEquals(1, idx.exact("+916000033333")?.id)
        assertEquals(listOf("arjun", "Dana"), idx.byPhone("9810").map { it.name })
        assertTrue(idx.byPhone("98111").isEmpty())
        idx.put(Customer(6, "Ira", "+919810066666"), "+919810066666")
        assertEquals(listOf("arjun", "Dana", "Ira"), idx.byPhone("9810").map { it.name })
        assertEquals(5, idx.size)
    }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test fun `delta rows - active with a mobile in, archived or without a mobile out`() {
        val d = CustomerService.indexDelta(listOf(
            json("""{"id": 7, "name": "Nia", "phone": "+91 98100 77777", "phone_sanitized": "+919810077777", "active": true,
                "vat": false, "l10n_in_gst_treatment": "consumer", "state_id": [10, "Delhi (IN)"], "property_account_position_id": false}"""),
            json("""{"id": 8, "name": "Old", "phone": false, "phone_sanitized": "+919810088888", "active": false}"""),
            json("""{"id": 9, "name": "No phone", "phone": false, "phone_sanitized": false, "active": true}"""),
        ), serverTimeMs = 42L)
        assertEquals(listOf(7), d.rows.map { it.customer.id })
        assertEquals("Delhi (IN)", d.rows.single().customer.stateName)
        assertEquals(listOf(8, 9), d.removed)
        assertEquals(42L, d.serverTimeMs)
    }

    @Test fun `the customer nested in a card answer`() {
        val c = CustomerService.fromSpec(json("""{"id": 7, "name": "Nia", "phone": "+91 98100 77777", "phone_sanitized": "+919810077777",
            "vat": "07ABCDE1234F1Z5", "l10n_in_gst_treatment": "regular", "state_id": {"id": 10, "display_name": "Delhi (IN)"},
            "property_account_position_id": 3}"""))!!
        assertEquals(Customer(7, "Nia", "+91 98100 77777", "07ABCDE1234F1Z5", com.bohemian.odoo.pos.gst.GstTreatment.REGULAR, 10, "Delhi (IN)", 3), c.customer)
        assertEquals("+919810077777", c.phoneSanitized)
        val bare = CustomerService.fromSpec(json("""{"id": 8, "name": "Om", "phone": false, "phone_sanitized": false, "vat": false,
            "l10n_in_gst_treatment": false, "state_id": false, "property_account_position_id": false}"""))!!
        assertNull(bare.customer.stateId)
        assertNull(bare.customer.fiscalPositionId)
    }

    @Test fun `the full read pages by id and keeps Odoo's clock`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().addHeader("Date", "Sat, 31 Oct 2026 07:51:41 GMT").setBody("""[
                {"id": 3, "name": "Kabir", "phone": "+91 70000 12345", "phone_sanitized": "+917000012345", "vat": false,
                 "l10n_in_gst_treatment": false, "state_id": false, "property_account_position_id": false}]"""))
            val svc = CustomerService(OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { "k" }))
            val (got, at) = svc.indexAll()
            assertEquals(listOf(3), got.map { it.customer.id })
            assertNotNull(at)
            assertEquals("2026-10-31 07:46:41", PosLoad.nextSince(null, at))
            val req = server.takeRequest()
            assertEquals("/json/2/res.partner/search_read", req.path)
            val body = json(req.body.readUtf8())
            assertEquals("""[["phone_sanitized","!=",false],["id",">",0]]""", body["domain"].toString())
            assertEquals("2000", body["limit"].toString())
        } finally {
            server.shutdown()
        }
    }
}
