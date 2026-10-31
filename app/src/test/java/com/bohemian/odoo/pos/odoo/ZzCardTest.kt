package com.bohemian.odoo.pos.odoo

import com.bohemian.odoo.pos.core.Paise
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card and the customer in one call (0.9.0), shaped like the live answer of 31 Oct; names and numbers made up. */
class ZzCardTest {

    private val answer = """{"length": 1, "records": [{"id": 277, "points": 501.84, "code": "0000-test-0001",
        "partner_id": {"id": 83, "name": "Nia", "phone": "+91 98100 77777", "phone_sanitized": "+919810077777", "vat": false,
          "l10n_in_gst_treatment": "consumer", "state_id": {"id": 10, "display_name": "Delhi (IN)"}, "property_account_position_id": false}}]}"""

    @Test fun `one call brings the balance and the customer as Odoo has them`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody(answer))
            val svc = ZzCashService(OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { "k" }))
            val card = svc.card(partnerId = 83, programId = 4)!!
            assertEquals(Paise(50184), card.balance.points)
            assertEquals(277, card.balance.cardId)
            assertEquals("Nia", card.customer!!.customer.name)
            assertEquals(10, card.customer!!.customer.stateId)
            assertEquals("+919810077777", card.customer!!.phoneSanitized)
            val req = server.takeRequest()
            assertEquals("/json/2/loyalty.card/web_search_read", req.path)
            val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
            assertEquals("""[["partner_id","=",83],["program_id","=",4]]""", body["domain"].toString())
            val partnerSpec = body["specification"]!!.jsonObject["partner_id"]!!.jsonObject["fields"]!!.jsonObject
            assertTrue(partnerSpec.keys.containsAll(listOf("name", "phone_sanitized", "vat", "l10n_in_gst_treatment", "state_id", "property_account_position_id")))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `no card, and a card whose nested customer is someone else`() {
        assertNull(ZzCashService.fromCardRows(83, emptyList()))
        val other = Json.parseToJsonElement("""{"id": 277, "points": 10.0, "code": "x", "partner_id": {"id": 99, "name": "Other"}}""").jsonObject
        val card = ZzCashService.fromCardRows(83, listOf(other))!!
        assertNull(card.customer) // never put another customer's details on this bill
        assertEquals(Paise(1000), card.balance.points)
    }
}
