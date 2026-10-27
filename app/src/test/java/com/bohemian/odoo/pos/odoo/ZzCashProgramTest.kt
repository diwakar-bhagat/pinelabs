package com.bohemian.odoo.pos.odoo

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The ZZ Cash loyalty.program is found in Odoo for the till's config, never a fixed id. */
class ZzCashProgramTest {

    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun service() = ZzCashService(OdooJson2Client(server.url("/").toString().trimEnd('/'), "test", apiKey = { "k" }))

    private fun program(id: Int, name: String) = JsonObject(mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(name)))

    private val config = PosConfig(id = 9, name = "E700", currentSessionId = null, sessionOpen = false,
        paymentMethodIds = emptyList(), companyName = "ZenZebra", companyId = 1)

    @Test fun `one program is the till's program`() {
        assertEquals(ZzCashProgram(4, "ZZ Cash"), ZzCashService.fromProgramRows(listOf(program(4, "ZZ Cash"))))
    }

    @Test fun `no program means no ZZ Cash`() {
        assertNull(ZzCashService.fromProgramRows(emptyList()))
    }

    @Test fun `two programs are ambiguous and refused`() {
        assertNull(ZzCashService.fromProgramRows(listOf(program(4, "ZZ Cash"), program(11, "Other"))))
    }

    @Test fun `query asks Odoo for the POS loyalty program open to this config`() = runTest {
        server.enqueue(MockResponse().setBody("""[{"id": 4, "name": "ZZ Cash"}]"""))
        assertEquals(ZzCashProgram(4, "ZZ Cash"), service().program(config))

        val req = server.takeRequest()
        assertEquals("/json/2/loyalty.program/search_read", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(
            Json.parseToJsonElement("""[["pos_ok","=",true],["program_type","=","loyalty"],["company_id","in",[1,false]],
                "|",["pos_config_ids","=",false],["pos_config_ids","in",[9]]]"""),
            body["domain"],
        )
        assertEquals(JsonPrimitive(2), body["limit"]) // enough to see a second, ambiguous program
    }

    @Test fun `balance and rewards use the discovered program id`() = runTest {
        server.enqueue(MockResponse().setBody("""[{"id": 277, "points": 120.0, "code": "x"}]"""))
        server.enqueue(MockResponse().setBody("[]"))
        service().balance(partnerId = 83, programId = 12)
        service().rewards(programId = 12)
        repeat(2) {
            val domain = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["domain"].toString()
            assert("""["program_id","=",12]""" in domain) { domain }
        }
    }
}
