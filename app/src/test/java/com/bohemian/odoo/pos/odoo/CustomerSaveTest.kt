package com.bohemian.odoo.pos.odoo

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The cashier saves a new customer from the till: name + mobile → res.partner, nothing else. */
class CustomerSaveTest {

    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun service() = CustomerService(OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { "k" }))

    private fun body(): Pair<String, JsonObject> = server.takeRequest().let {
        it.path!!.substringAfterLast('/') to Json.parseToJsonElement(it.body.readUtf8()).jsonObject
    }

    @Test fun `create sends the name and E164 mobile only, then reads the partner back`() = runTest {
        server.enqueue(MockResponse().setBody("[77]"))
        server.enqueue(MockResponse().setBody("""[{"id": 77, "name": "Riya", "phone": "+919876543210", "vat": false,
            "l10n_in_gst_treatment": "consumer", "state_id": false, "property_account_position_id": false}]"""))
        val c = service().create("Riya", "+919876543210")
        assertEquals(77, c.id)
        assertEquals("Riya", c.name)
        val (method, create) = body()
        assertEquals("create", method)
        val vals = create["vals_list"]!!.jsonArray.single().jsonObject
        assertEquals(mapOf("name" to JsonPrimitive("Riya"), "phone" to JsonPrimitive("+919876543210")), vals)
        assertEquals("read", body().first)
    }

    @Test fun `exact lookup is by the sanitized phone`() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        assertNull(service().findByPhone("+919876543210"))
        val (method, req) = body()
        assertEquals("search_read", method)
        assertEquals("""[["phone_sanitized","=","+919876543210"]]""", req["domain"].toString())
    }
}
